package app.keplersync.tandem.core;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

/**
 * Asks the home router to forward a port to this PC: UPnP first, then PCP and NAT-PMP (what Apple routers and many
 * newer ones speak), straight to the default gateway on UDP 5351. Each mapping lasts an hour and is renewed while the
 * game is open, so a crash doesn't leave a port open for good.
 */
public final class PortMapper {
	static final int PORT = 5351;
	private static final SecureRandom RANDOM = new SecureRandom();

	/** A port the router forwards to this PC. */
	public abstract static class Mapping {
		/** upnp, pcp or natpmp. */
		public final String how;
		/** TCP or UDP. */
		public final String protocol;
		public final String externalIp;
		public final int externalPort;
		public final int internalPort;

		Mapping(String how, String protocol, String externalIp, int externalPort, int internalPort) {
			this.how = how;
			this.protocol = protocol;
			this.externalIp = externalIp;
			this.externalPort = externalPort;
			this.internalPort = internalPort;
		}

		/** host:port for friends. */
		public String address() {
			return (externalIp.contains(":") ? "[" + externalIp + "]" : externalIp) + ":" + externalPort;
		}

		/** Whether the internet can reach it: a router behind another NAT (CGNAT, double NAT) reports a private address. */
		public boolean reachable() {
			return Addresses.isPublic(externalIp);
		}

		/** Asks for another hour. */
		public abstract void renew() throws IOException;

		/** Gives the port back; failures don't matter, the lease runs out by itself. */
		public abstract void close();

		@Override
		public String toString() {
			return how + " " + protocol + " " + address();
		}
	}

	/** A mapping, or why there isn't one. */
	public static final class Attempt {
		public final Mapping mapping;
		public final String why;

		private Attempt(Mapping mapping, String why) {
			this.mapping = mapping;
			this.why = why;
		}

		static Attempt ok(Mapping m) {
			return new Attempt(m, null);
		}

		static Attempt failed(String why) {
			return new Attempt(null, why);
		}
	}

	private PortMapper() {}

	/**
	 * Opens [internalPort] for [protocol] ("TCP" or "UDP"): UPnP, else PCP or NAT-PMP at each gateway candidate. The
	 * mapping, or every reason it didn't happen ("upnp: no router answered; pcp: no answer").
	 */
	public static Attempt open(int internalPort, String protocol, String description) {
		Attempt upnp = Upnp.open(internalPort, protocol, description, 3000);
		if (upnp.mapping != null) return upnp;
		Attempt pcp = pcp(Gateway.candidates(), internalPort, protocol, 250);
		if (pcp.mapping != null) return pcp;
		return Attempt.failed(upnp.why + "; " + pcp.why);
	}

	/** PCP, then NAT-PMP, at each of [gateways] in turn. */
	static Attempt pcp(Collection<String> gateways, int internalPort, String protocol, int firstWaitMs) {
		String why = "pcp: no gateway";
		for (String g : gateways) {
			InetSocketAddress at = new InetSocketAddress(g, PORT);
			try {
				Mapping m = pcpMap(at, internalPort, protocol, firstWaitMs);
				if (m != null) return Attempt.ok(m);
				m = natPmpMap(at, internalPort, protocol, firstWaitMs);
				if (m != null) return Attempt.ok(m);
				why = "pcp: " + g + " doesn't speak PCP or NAT-PMP";
			} catch (IOException e) {
				why = "pcp: " + e.getMessage();
			}
		}
		return Attempt.failed(why);
	}

	/** PCP MAP (RFC 6887). Null when the gateway doesn't answer or only speaks NAT-PMP. */
	static Mapping pcpMap(InetSocketAddress gateway, int internalPort, String protocol, int firstWaitMs) throws IOException {
		byte[] nonce = new byte[12];
		RANDOM.nextBytes(nonce);
		byte[] reply = pcpRequest(gateway, nonce, internalPort, internalPort, protocol, Upnp.LEASE_SECONDS, firstWaitMs);
		if (reply == null) return null;
		int externalPort = ((reply[42] & 0xFF) << 8) | (reply[43] & 0xFF);
		String ip = InetAddress.getByAddress(Arrays.copyOfRange(reply, 56, 60)).getHostAddress();
		return new Mapping("pcp", protocol, ip, externalPort, internalPort) {
			@Override
			public void renew() throws IOException {
				if (pcpRequest(gateway, nonce, internalPort, externalPort, protocol, Upnp.LEASE_SECONDS, firstWaitMs) == null) {
					throw new IOException("the gateway didn't renew the mapping");
				}
			}

			@Override
			public void close() {
				try {
					pcpRequest(gateway, nonce, internalPort, 0, protocol, 0, firstWaitMs);
				} catch (IOException ignored) {
					// It lapses.
				}
			}
		};
	}

	/** One PCP MAP exchange; the 60-byte answer on success, null for no answer or a NAT-PMP-only gateway. */
	private static byte[] pcpRequest(InetSocketAddress gateway, byte[] nonce, int internalPort, int externalPort, String protocol, int lifetime,
			int firstWaitMs) throws IOException {
		try (DatagramSocket s = new DatagramSocket()) {
			s.connect(gateway);
			ByteBuffer b = ByteBuffer.allocate(60);
			b.put((byte) 2).put((byte) 1).putShort((short) 0).putInt(lifetime);
			b.put(mapped(s.getLocalAddress()));
			b.put(nonce).put((byte) (protocol.equals("TCP") ? 6 : 17)).put(new byte[3]);
			b.putShort((short) internalPort).putShort((short) externalPort);
			b.put(mapped(null));
			byte[] reply = exchange(s, b.array(), 60, firstWaitMs);
			if (reply == null || reply[0] != 2) return null;
			if ((reply[1] & 0xFF) != 0x81) throw new IOException("pcp: an unexpected answer");
			int result = reply[3] & 0xFF;
			if (result != 0) throw new IOException("pcp: the gateway refused (" + result + ")");
			if (!Arrays.equals(Arrays.copyOfRange(reply, 24, 36), nonce)) throw new IOException("pcp: an answer to someone else");
			return reply;
		}
	}

	/** NAT-PMP (RFC 6886): the public address, then the mapping. Null when the gateway doesn't answer. */
	static Mapping natPmpMap(InetSocketAddress gateway, int internalPort, String protocol, int firstWaitMs) throws IOException {
		try (DatagramSocket s = new DatagramSocket()) {
			s.connect(gateway);
			byte[] address = exchange(s, new byte[] {0, 0}, 12, firstWaitMs);
			if (address == null || address[0] != 0 || (address[1] & 0xFF) != 128) return null;
			int result = ((address[2] & 0xFF) << 8) | (address[3] & 0xFF);
			if (result != 0) throw new IOException("nat-pmp: the gateway refused (" + result + ")");
			String ip = InetAddress.getByAddress(Arrays.copyOfRange(address, 8, 12)).getHostAddress();
			int externalPort = natPmpRequest(s, internalPort, internalPort, protocol, Upnp.LEASE_SECONDS, firstWaitMs);
			return new Mapping("natpmp", protocol, ip, externalPort, internalPort) {
				@Override
				public void renew() throws IOException {
					try (DatagramSocket r = new DatagramSocket()) {
						r.connect(gateway);
						natPmpRequest(r, internalPort, externalPort, protocol, Upnp.LEASE_SECONDS, firstWaitMs);
					}
				}

				@Override
				public void close() {
					try (DatagramSocket r = new DatagramSocket()) {
						r.connect(gateway);
						natPmpRequest(r, internalPort, 0, protocol, 0, firstWaitMs);
					} catch (IOException ignored) {
						// It lapses.
					}
				}
			};
		}
	}

	private static int natPmpRequest(DatagramSocket s, int internalPort, int externalPort, String protocol, int lifetime, int firstWaitMs)
			throws IOException {
		int op = protocol.equals("TCP") ? 2 : 1;
		ByteBuffer b = ByteBuffer.allocate(12);
		b.put((byte) 0).put((byte) op).putShort((short) 0).putShort((short) internalPort).putShort((short) externalPort).putInt(lifetime);
		byte[] reply = exchange(s, b.array(), 16, firstWaitMs);
		if (reply == null) throw new IOException("nat-pmp: no answer to the mapping");
		if ((reply[1] & 0xFF) != 128 + op) throw new IOException("nat-pmp: an unexpected answer");
		int result = ((reply[2] & 0xFF) << 8) | (reply[3] & 0xFF);
		if (result != 0) throw new IOException("nat-pmp: the gateway refused (" + result + ")");
		return ((reply[10] & 0xFF) << 8) | (reply[11] & 0xFF);
	}

	/** Sends [request] up to three times, waiting [firstWaitMs] then twice as long each time; the answer or null. */
	private static byte[] exchange(DatagramSocket s, byte[] request, int size, int firstWaitMs) throws IOException {
		byte[] buf = new byte[1100];
		int wait = firstWaitMs;
		for (int i = 0; i < 3; i++, wait *= 2) {
			s.send(new DatagramPacket(request, request.length));
			s.setSoTimeout(wait);
			try {
				DatagramPacket p = new DatagramPacket(buf, buf.length);
				s.receive(p);
				if (p.getLength() >= 2 && (p.getLength() >= size || buf[0] != request[0])) return Arrays.copyOf(buf, Math.max(size, p.getLength()));
			} catch (SocketTimeoutException ignored) {
				// Again, waiting longer.
			} catch (java.net.PortUnreachableException e) {
				return null;
			}
		}
		return null;
	}

	/** An IPv4 address as PCP writes it (::ffff:a.b.c.d); 0.0.0.0 for null. */
	private static byte[] mapped(InetAddress a) {
		byte[] out = new byte[16];
		out[10] = (byte) 0xFF;
		out[11] = (byte) 0xFF;
		if (a != null && a.getAddress().length == 4) System.arraycopy(a.getAddress(), 0, out, 12, 4);
		return out;
	}

	/** Closes [mappings], each in its own background thread so a slow router doesn't hold up the game. */
	public static void closeAll(List<Mapping> mappings) {
		List<Mapping> copy = new ArrayList<>(mappings);
		if (copy.isEmpty()) return;
		Thread t = new Thread(() -> copy.forEach(Mapping::close), "Tandem close ports");
		t.setDaemon(true);
		t.start();
	}
}
