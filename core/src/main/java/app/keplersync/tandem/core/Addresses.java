package app.keplersync.tandem.core;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** This PC's addresses, and which addresses the internet can reach. */
public final class Addresses {
	private Addresses() {}

	/** This PC's IPv4 addresses on its home networks (not loopback, not virtual adapters). */
	public static List<InetAddress> lanV4() {
		List<InetAddress> out = new ArrayList<>();
		for (InetAddress a : all()) if (a instanceof Inet4Address && a.isSiteLocalAddress()) out.add(a);
		return out;
	}

	/** This PC's public IPv6 addresses: global unicast, without Teredo and 6to4, which don't reach friends reliably. */
	public static List<InetAddress> globalV6() {
		List<InetAddress> out = new ArrayList<>();
		for (InetAddress a : all()) if (a instanceof Inet6Address && isGlobalV6(a)) out.add(a);
		return out;
	}

	static boolean isGlobalV6(InetAddress a) {
		byte[] b = a.getAddress();
		if (b.length != 16 || (b[0] & 0xE0) != 0x20) return false;
		boolean teredo = b[0] == 0x20 && b[1] == 0x01 && b[2] == 0 && b[3] == 0;
		boolean sixToFour = b[0] == 0x20 && b[1] == 0x02;
		return !teredo && !sixToFour;
	}

	/**
	 * Whether friends on the internet can reach [ip]: not a private, shared (CGNAT, 100.64.0.0/10), loopback or
	 * link-local address. A router reporting one of those as its "public" address is itself behind another NAT.
	 */
	public static boolean isPublic(String ip) {
		InetAddress a;
		try {
			// Only literals: anything else would make Java look the name up.
			if (ip == null) return false;
			String bare = ip.startsWith("[") && ip.endsWith("]") ? ip.substring(1, ip.length() - 1) : ip;
			boolean v4 = bare.matches("(25[0-5]|2[0-4]\\d|1?\\d?\\d)(\\.(25[0-5]|2[0-4]\\d|1?\\d?\\d)){3}");
			boolean v6 = bare.contains(":") && bare.matches("[0-9a-fA-F:.]+");
			if (!v4 && !v6) return false;
			a = InetAddress.getByName(bare);
		} catch (UnknownHostException e) {
			return false;
		}
		if (a.isAnyLocalAddress() || a.isLoopbackAddress() || a.isLinkLocalAddress() || a.isSiteLocalAddress() || a.isMulticastAddress()) return false;
		if (a instanceof Inet4Address) {
			byte[] b = a.getAddress();
			return !((b[0] & 0xFF) == 100 && (b[1] & 0xC0) == 64);
		}
		return isGlobalV6(a);
	}

	/** host:port, with IPv6 in brackets. */
	public static String hostPort(InetAddress a, int port) {
		String h = a.getHostAddress();
		int zone = h.indexOf('%');
		if (zone >= 0) h = h.substring(0, zone);
		return (a instanceof Inet6Address ? "[" + h + "]" : h) + ":" + port;
	}

	private static List<InetAddress> all() {
		List<InetAddress> out = new ArrayList<>();
		try {
			for (NetworkInterface n : Collections.list(NetworkInterface.getNetworkInterfaces())) {
				if (!n.isUp() || n.isLoopback() || n.isVirtual()) continue;
				out.addAll(Collections.list(n.getInetAddresses()));
			}
		} catch (IOException e) {
			// No addresses.
		}
		return out;
	}
}
