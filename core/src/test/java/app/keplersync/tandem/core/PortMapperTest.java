package app.keplersync.tandem.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** PCP and NAT-PMP against a fake gateway, the routing tables of each system, and which addresses are public. */
class PortMapperTest {
	DatagramSocket gateway;
	final List<byte[]> requests = new CopyOnWriteArrayList<>();

	@AfterEach
	void tearDown() {
		if (gateway != null) gateway.close();
	}

	/** A gateway that speaks PCP ([pcp]) or only NAT-PMP, mapping to 203.0.113.9 and external port 40000. */
	private InetSocketAddress fakeGateway(boolean pcp, int dropFirst) throws IOException {
		gateway = new DatagramSocket(0, InetAddress.getLoopbackAddress());
		RelayTunnel.daemon(() -> {
			byte[] buf = new byte[1100];
			int seen = 0;
			while (!gateway.isClosed()) {
				try {
					DatagramPacket p = new DatagramPacket(buf, buf.length);
					gateway.receive(p);
					byte[] req = Arrays.copyOf(buf, p.getLength());
					requests.add(req);
					if (seen++ < dropFirst) continue;
					byte[] reply;
					if (req[0] == 2 && pcp) {
						reply = Arrays.copyOf(req, 60);
						reply[1] = (byte) 0x81;
						reply[3] = 0;
						reply[42] = (byte) (40000 >> 8);
						reply[43] = (byte) (40000 & 0xFF);
						System.arraycopy(new byte[] {(byte) 203, 0, 113, 9}, 0, reply, 56, 4);
					} else if (req[0] == 2) {
						// A NAT-PMP gateway answers PCP with "unsupported version".
						reply = new byte[] {0, (byte) 0x81, 0, 1, 0, 0, 0, 0};
					} else if (req[1] == 0) {
						reply = new byte[] {0, (byte) 128, 0, 0, 0, 0, 0, 1, (byte) 203, 0, 113, 9};
					} else {
						ByteBuffer b = ByteBuffer.allocate(16);
						b.put((byte) 0).put((byte) (128 + req[1])).putShort((short) 0).putInt(1);
						b.put(req, 4, 2).putShort((short) 40000).putInt(3600);
						reply = b.array();
					}
					gateway.send(new DatagramPacket(reply, reply.length, p.getSocketAddress()));
				} catch (IOException e) {
					return;
				}
			}
		}, "fake gateway");
		return new InetSocketAddress(InetAddress.getLoopbackAddress(), gateway.getLocalPort());
	}

	@Test
	void pcpMapsThePortAndSaysThePublicAddress() throws IOException {
		InetSocketAddress at = fakeGateway(true, 0);
		PortMapper.Mapping m = PortMapper.pcpMap(at, 51234, "UDP", 100);
		assertNotNull(m);
		assertEquals("pcp UDP 203.0.113.9:40000", m.toString());
		assertTrue(m.reachable());
		byte[] req = requests.get(0);
		assertEquals(60, req.length);
		assertEquals(17, req[36], "UDP");
		assertEquals(51234, ((req[40] & 0xFF) << 8) | (req[41] & 0xFF));
		m.close();
		byte[] close = requests.get(requests.size() - 1);
		assertEquals(0, ByteBuffer.wrap(close, 4, 4).getInt(), "a lifetime of 0 removes it");
	}

	@Test
	void aLostAnswerIsAskedForAgain() throws IOException {
		InetSocketAddress at = fakeGateway(true, 1);
		assertNotNull(PortMapper.pcpMap(at, 51234, "TCP", 100));
		assertEquals(2, requests.size());
	}

	@Test
	void aNatPmpGatewayIsAskedInItsOwnWords() throws IOException {
		InetSocketAddress at = fakeGateway(false, 0);
		assertNull(PortMapper.pcpMap(at, 51234, "TCP", 100), "it doesn't speak PCP");
		PortMapper.Mapping m = PortMapper.natPmpMap(at, 51234, "TCP", 100);
		assertEquals("natpmp TCP 203.0.113.9:40000", m.toString());
		PortMapper.Attempt a = PortMapper.pcp(List.of("127.0.0.1"), 51234, "TCP", 50);
		assertNull(a.mapping, "nothing answers on the real port 5351 here");
		assertNotNull(a.why);
	}

	@Test
	void routingTablesGiveTheGateway() {
		assertEquals(List.of("192.168.1.1"), List.copyOf(Gateway.linux(
				"Iface\tDestination\tGateway \tFlags\tRefCnt\tUse\tMetric\tMask\n"
						+ "eth0\t00000000\t0101A8C0\t0003\t0\t0\t100\t00000000\n"
						+ "eth0\t0001A8C0\t00000000\t0001\t0\t0\t100\t00FFFFFF\n")));
		assertEquals(List.of("10.0.0.138"), List.copyOf(Gateway.windows(
				"===========================================================================\r\n"
						+ "Aktive Routen:\r\n"
						+ "     Netzwerkziel    Netzwerkmaske          Gateway    Schnittstelle Metrik\r\n"
						+ "          0.0.0.0          0.0.0.0       10.0.0.138       10.0.0.42     25\r\n"
						+ "          0.0.0.0          0.0.0.0      Auf Verbindung       172.20.0.1   5256\r\n")));
		assertEquals(List.of("192.168.0.1"), List.copyOf(Gateway.mac(
				"Routing tables\n\nInternet:\nDestination        Gateway            Flags               Netif Expire\n"
						+ "default            192.168.0.1        UGScg                 en0\n"
						+ "default            link#18            UCSIg           bridge100      !\n")));
	}

	@Test
	void onlyPublicAddressesCount() {
		for (String ip : new String[] {"203.0.113.9", "8.8.8.8", "2606:4700::1111"}) assertTrue(Addresses.isPublic(ip), ip);
		for (String ip : new String[] {"192.168.1.20", "10.1.2.3", "172.16.0.1", "100.64.12.1", "100.127.0.1", "127.0.0.1", "169.254.1.1",
				"0.0.0.0", "fd00::1", "fe80::1", "2001:0:4136::1", "2002:c000:204::1", "example.com", ""}) {
			assertFalse(Addresses.isPublic(ip), ip);
		}
		assertTrue(Addresses.isPublic("100.128.0.1"), "just past the shared range");
	}
}
