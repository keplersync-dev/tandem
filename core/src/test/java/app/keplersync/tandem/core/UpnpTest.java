package app.keplersync.tandem.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Against a fake router: its description file and its WAN connection service. */
class UpnpTest {
	HttpServer router;
	String location;
	final List<String> actions = new ArrayList<>();
	final List<String> bodies = new ArrayList<>();
	/** UPnP error codes the router answers AddPortMapping with, one per call, before it agrees. */
	final java.util.Deque<Integer> refusals = new java.util.ArrayDeque<>();

	@BeforeEach
	void setUp() throws IOException {
		router = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		location = "http://127.0.0.1:" + router.getAddress().getPort() + "/rootDesc.xml";
		router.createContext("/rootDesc.xml", ex -> reply(ex, "<?xml version=\"1.0\"?><root><device><serviceList>"
				+ "<service><serviceType>urn:schemas-upnp-org:service:Layer3Forwarding:1</serviceType><controlURL>/l3f</controlURL></service>"
				+ "<service><serviceType>urn:schemas-upnp-org:service:WANIPConnection:1</serviceType>"
				+ "<controlURL>/ctl/IPConn</controlURL></service></serviceList></device></root>"));
		router.createContext("/ctl/IPConn", ex -> {
			String action = ex.getRequestHeaders().getFirst("SOAPAction");
			actions.add(action);
			bodies.add(read(ex.getRequestBody()));
			if (action.contains("AddPortMapping") && !refusals.isEmpty()) {
				byte[] fault = ("<s:Envelope><s:Body><s:Fault><detail><UPnPError><errorCode>" + refusals.pop()
						+ "</errorCode><errorDescription>No</errorDescription></UPnPError></detail></s:Fault></s:Body></s:Envelope>").getBytes(StandardCharsets.UTF_8);
				ex.sendResponseHeaders(500, fault.length);
				try (OutputStream out = ex.getResponseBody()) {
					out.write(fault);
				}
				return;
			}
			reply(ex, action.contains("GetExternalIPAddress")
					? "<s:Envelope><s:Body><u:GetExternalIPAddressResponse><NewExternalIPAddress>203.0.113.7</NewExternalIPAddress>"
							+ "</u:GetExternalIPAddressResponse></s:Body></s:Envelope>"
					: "<s:Envelope><s:Body/></s:Envelope>");
		});
		router.start();
	}

	@AfterEach
	void tearDown() {
		router.stop(0);
	}

	private static String read(InputStream in) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		byte[] buf = new byte[1024];
		int n;
		while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
		return new String(out.toByteArray(), StandardCharsets.UTF_8);
	}

	private static void reply(HttpExchange ex, String body) throws IOException {
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		ex.sendResponseHeaders(200, bytes.length);
		try (OutputStream out = ex.getResponseBody()) {
			out.write(bytes);
		}
	}

	@Test
	void theRouterOpensAPortAndSaysItsAddress() throws IOException {
		PortMapper.Mapping m = Upnp.openAt(location, 51234, "TCP", "Tandem", 3000);
		assertEquals("203.0.113.7:51234", m.address());
		assertTrue(actions.get(0).contains("WANIPConnection:1#AddPortMapping"), actions.get(0));
		assertTrue(bodies.get(0).contains("<NewInternalClient>127.0.0.1</NewInternalClient>"), bodies.get(0));
		assertTrue(bodies.get(0).contains("<NewExternalPort>51234</NewExternalPort>"));
		assertTrue(bodies.get(0).contains("<NewLeaseDuration>3600</NewLeaseDuration>"), "an hour, renewed while the game runs");
		m.renew();
		assertTrue(actions.get(actions.size() - 1).contains("AddPortMapping"));
		m.close();
		assertTrue(actions.get(actions.size() - 1).contains("DeletePortMapping"));
	}

	@Test
	void aTakenPortMovesAlongAndARouterThatOnlyKeepsPermanentMappingsGetsOne() throws IOException {
		refusals.add(725);
		refusals.add(718);
		PortMapper.Mapping m = Upnp.openAt(location, 51234, "UDP", "Tandem", 3000);
		assertEquals("203.0.113.7:51235", m.address());
		String last = bodies.get(2);
		assertTrue(last.contains("<NewLeaseDuration>0</NewLeaseDuration>") && last.contains("<NewProtocol>UDP</NewProtocol>"), last);
		assertTrue(last.contains("<NewInternalPort>51234</NewInternalPort>"), last);
	}

	@Test
	void aRefusalSaysWhy() {
		refusals.add(606);
		IOException e = org.junit.jupiter.api.Assertions.assertThrows(IOException.class, () -> Upnp.openAt(location, 51234, "TCP", "Tandem", 3000));
		assertTrue(e.getMessage().contains("606"), e.getMessage());
	}

	@Test
	void discoveryRepliesNameTheDescription() {
		String reply = "HTTP/1.1 200 OK\r\nCACHE-CONTROL: max-age=120\r\nLocation: http://192.168.1.1:5000/rootDesc.xml\r\nST: x\r\n\r\n";
		assertEquals("http://192.168.1.1:5000/rootDesc.xml", Upnp.header(reply, "LOCATION"));
		assertNull(Upnp.header(reply, "SERVER"));
	}

	@Test
	void aRouterWithoutAWanServiceGivesNothing() throws IOException {
		assertNull(Upnp.control("<root><serviceList></serviceList></root>", location));
	}

	@Test
	void aDeviceThatSendsWithoutEndIsOnlyReadSoFar() throws IOException {
		router.createContext("/endless.xml", ex -> {
			ex.sendResponseHeaders(200, 0);
			byte[] junk = new byte[64 * 1024];
			try (OutputStream out = ex.getResponseBody()) {
				while (true) out.write(junk);
			} catch (IOException stopped) {
				// Tandem stopped reading.
			}
		});
		assertNull(Upnp.openAt(location.replace("rootDesc.xml", "endless.xml"), 51234, "TCP", "Tandem", 3000));
	}
}
