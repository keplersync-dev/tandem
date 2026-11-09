package app.keplersync.tandem.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Asks the home router to open a port to this PC (UPnP Internet Gateway Device), the way games and World Host do,
 * so friends elsewhere can connect without the relay. No library: a discovery broadcast, one XML file and a few SOAP
 * calls. Many routers have it off; {@link PortMapper} then tries PCP and NAT-PMP.
 */
public final class Upnp {
	private static final String[] SERVICES = {
		"urn:schemas-upnp-org:service:WANIPConnection:2",
		"urn:schemas-upnp-org:service:WANIPConnection:1",
		"urn:schemas-upnp-org:service:WANPPPConnection:1",
	};
	private static final String[] SEARCHES = {
		"urn:schemas-upnp-org:device:InternetGatewayDevice:1",
		"urn:schemas-upnp-org:device:InternetGatewayDevice:2",
		"urn:schemas-upnp-org:service:WANIPConnection:1",
	};
	/** How long a mapping lasts before the router drops it: renewed well before, gone soon after a crash. */
	static final int LEASE_SECONDS = 3600;
	/** A router's description and answers are a few KB. */
	static final int MAX_REPLY = 1 << 20;

	/** The router refused, with UPnP's error code (718: the port is taken; 725: only permanent mappings). */
	static final class Refused extends IOException {
		final int code;

		Refused(int code, String description) {
			super("the router refused (" + code + (description.isEmpty() ? "" : " " + description) + ")");
			this.code = code;
		}
	}

	private Upnp() {}

	/**
	 * Opens [internalPort] for [protocol] ("TCP" or "UDP") on whichever router answers. The mapping, or why there's
	 * none: no router answered, no WAN service, the router refused, or an error.
	 */
	static PortMapper.Attempt open(int internalPort, String protocol, String description, int timeoutMs) {
		List<String> locations;
		try {
			locations = discover(timeoutMs);
		} catch (IOException e) {
			return PortMapper.Attempt.failed("upnp: " + e.getMessage());
		}
		if (locations.isEmpty()) return PortMapper.Attempt.failed("upnp: no router answered");
		String why = "upnp: no WAN service";
		for (String location : locations) {
			try {
				PortMapper.Mapping m = openAt(location, internalPort, protocol, description, timeoutMs);
				if (m != null) return PortMapper.Attempt.ok(m);
			} catch (IOException | RuntimeException e) {
				// Any device on the network can answer the search, with a location that isn't even http.
				why = "upnp: " + e.getMessage();
			}
		}
		return PortMapper.Attempt.failed(why);
	}

	/**
	 * Routers' description URLs, from SSDP searches sent three times from each of this PC's IPv4 addresses (a single
	 * socket on Windows sends multicast out of one adapter only, which with VPN or Hyper-V adapters may not be the
	 * router's), collecting every reply for [timeoutMs].
	 */
	static List<String> discover(int timeoutMs) throws IOException {
		List<DatagramSocket> sockets = new ArrayList<>();
		for (InetAddress a : Addresses.lanV4()) {
			try {
				sockets.add(new DatagramSocket(new InetSocketAddress(a, 0)));
			} catch (IOException ignored) {
				// That adapter can't send; the others may.
			}
		}
		if (sockets.isEmpty()) sockets.add(new DatagramSocket());
		Set<String> found = new LinkedHashSet<>();
		try {
			InetSocketAddress group = new InetSocketAddress("239.255.255.250", 1900);
			long end = System.currentTimeMillis() + timeoutMs;
			byte[] buf = new byte[2048];
			for (int round = 0; round < 3 && System.currentTimeMillis() < end; round++) {
				for (DatagramSocket s : sockets) {
					for (String st : SEARCHES) {
						byte[] out = ("M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 1\r\nST: " + st + "\r\n\r\n")
								.getBytes(StandardCharsets.US_ASCII);
						try {
							s.send(new DatagramPacket(out, out.length, group));
						} catch (IOException ignored) {
							// Try the next.
						}
					}
				}
				// Half a second of listening between rounds, the rest after the last.
				long listenUntil = round < 2 ? Math.min(end, System.currentTimeMillis() + 500) : end;
				while (System.currentTimeMillis() < listenUntil) {
					for (DatagramSocket s : sockets) {
						s.setSoTimeout(Math.max(1, (int) Math.min(50, listenUntil - System.currentTimeMillis())));
						try {
							DatagramPacket reply = new DatagramPacket(buf, buf.length);
							s.receive(reply);
							String location = header(new String(reply.getData(), 0, reply.getLength(), StandardCharsets.US_ASCII), "LOCATION");
							if (location != null) found.add(location);
						} catch (SocketTimeoutException ignored) {
							// Nothing on this one yet.
						}
					}
				}
				if (!found.isEmpty() && round >= 1) break;
			}
		} finally {
			for (DatagramSocket s : sockets) s.close();
		}
		return new ArrayList<>(found);
	}

	static String header(String response, String name) {
		for (String line : response.split("\r\n")) {
			int colon = line.indexOf(':');
			if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase(name)) return line.substring(colon + 1).trim();
		}
		return null;
	}

	/**
	 * With the router's description at [location]: map the port for an hour, then ask for the public address. A port
	 * already taken (718) moves to the next few external ports; a router that only keeps permanent mappings (725) gets
	 * one. Null when the router has no WAN service.
	 */
	static PortMapper.Mapping openAt(String location, int internalPort, String protocol, String description, int timeoutMs) throws IOException {
		String xml = get(location, timeoutMs);
		String[] found = control(xml, location);
		if (found == null) return null;
		String control = found[0];
		String service = found[1];
		String self = localAddressTowards(new URL(location), timeoutMs);
		int lease = LEASE_SECONDS;
		int external = internalPort;
		for (int moved = 0; ; ) {
			try {
				add(control, service, external, internalPort, protocol, self, description, lease, timeoutMs);
				break;
			} catch (Refused r) {
				if (r.code == 725 && lease != 0) lease = 0;
				else if (r.code == 718 && moved < 4) external = internalPort + ++moved > 65535 ? 1024 + moved : internalPort + moved;
				else throw r;
			}
		}
		String ip = tag(soap(control, service, "GetExternalIPAddress", "", timeoutMs), "NewExternalIPAddress");
		if (ip == null || ip.isEmpty()) return null;
		final int ext = external;
		final int leased = lease;
		return new PortMapper.Mapping("upnp", protocol, ip, ext, internalPort) {
			@Override
			public void renew() throws IOException {
				if (leased != 0) add(control, service, ext, internalPort, protocol, self, description, leased, timeoutMs);
			}

			@Override
			public void close() {
				try {
					soap(control, service, "DeletePortMapping", "<NewRemoteHost></NewRemoteHost><NewExternalPort>" + ext
							+ "</NewExternalPort><NewProtocol>" + protocol + "</NewProtocol>", 3000);
				} catch (IOException ignored) {
					// The lease runs out by itself.
				}
			}
		};
	}

	private static void add(String control, String service, int external, int internal, String protocol, String self, String description,
			int lease, int timeoutMs) throws IOException {
		soap(control, service, "AddPortMapping",
				"<NewRemoteHost></NewRemoteHost><NewExternalPort>" + external + "</NewExternalPort><NewProtocol>" + protocol + "</NewProtocol>"
						+ "<NewInternalPort>" + internal + "</NewInternalPort><NewInternalClient>" + self + "</NewInternalClient>"
						+ "<NewEnabled>1</NewEnabled><NewPortMappingDescription>" + description + "</NewPortMappingDescription>"
						+ "<NewLeaseDuration>" + lease + "</NewLeaseDuration>", timeoutMs);
	}

	/** The control URL and service type of the router's WAN connection service, or null if it has none. */
	static String[] control(String xml, String location) throws IOException {
		for (String service : SERVICES) {
			int at = xml.indexOf(service);
			if (at < 0) continue;
			int end = xml.indexOf("</service>", at);
			String part = xml.substring(at, end < 0 ? xml.length() : end);
			String control = tag(part, "controlURL");
			if (control == null) continue;
			String base = tag(xml, "URLBase");
			return new String[] {new URL(new URL(base != null && !base.isEmpty() ? base : location), control).toString(), service};
		}
		return null;
	}

	static String tag(String xml, String name) {
		Matcher m = Pattern.compile("<(?:\\w+:)?" + name + ">\\s*([^<]*?)\\s*</(?:\\w+:)?" + name + ">").matcher(xml);
		return m.find() ? m.group(1) : null;
	}

	/** This PC's address on the network the router is on. */
	private static String localAddressTowards(URL router, int timeoutMs) throws IOException {
		try (Socket s = new Socket()) {
			int port = router.getPort() > 0 ? router.getPort() : 80;
			s.connect(new InetSocketAddress(InetAddress.getByName(router.getHost()), port), timeoutMs);
			return s.getLocalAddress().getHostAddress();
		}
	}

	private static String get(String url, int timeoutMs) throws IOException {
		HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
		c.setConnectTimeout(timeoutMs);
		c.setReadTimeout(timeoutMs);
		return read(c.getInputStream());
	}

	private static String soap(String control, String service, String action, String args, int timeoutMs) throws IOException {
		String body = "<?xml version=\"1.0\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" "
				+ "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body><u:" + action + " xmlns:u=\"" + service + "\">"
				+ args + "</u:" + action + "></s:Body></s:Envelope>";
		HttpURLConnection c = (HttpURLConnection) new URL(control).openConnection();
		c.setRequestMethod("POST");
		c.setConnectTimeout(timeoutMs);
		c.setReadTimeout(timeoutMs);
		c.setDoOutput(true);
		c.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"");
		c.setRequestProperty("SOAPAction", "\"" + service + "#" + action + "\"");
		try (OutputStream out = c.getOutputStream()) {
			out.write(body.getBytes(StandardCharsets.UTF_8));
		}
		int status = c.getResponseCode();
		if (status >= 300) {
			String fault = read(c.getErrorStream());
			String code = tag(fault, "errorCode");
			String description = tag(fault, "errorDescription");
			if (code != null && code.matches("\\d+")) throw new Refused(Integer.parseInt(code), description == null ? "" : description);
			throw new IOException("the router said no to " + action + " (" + status + ")");
		}
		return read(c.getInputStream());
	}

	/** At most [MAX_REPLY] bytes: any device on the network can answer the search, and one could send without end. */
	private static String read(InputStream in) throws IOException {
		if (in == null) return "";
		try (InputStream i = in) {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			byte[] buf = new byte[4096];
			int n;
			while (out.size() < MAX_REPLY && (n = i.read(buf)) != -1) out.write(buf, 0, n);
			return new String(out.toByteArray(), StandardCharsets.UTF_8);
		}
	}
}
