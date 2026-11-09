package app.keplersync.tandem.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class LogScrubTest {
	@Test
	void removesNamesLinksAndTokens() {
		assertEquals("C:\\Users\\<user>\\AppData\\.minecraft", LogScrub.scrub("C:\\Users\\Alex Kim\\AppData\\.minecraft"));
		assertEquals("/Users/<user>/Library and /home/<user>/x", LogScrub.scrub("/Users/alex/Library and /home/sam/x"));
		assertEquals("GET https://r2.example/worlds/ab?<removed> failed",
				LogScrub.scrub("GET https://r2.example/worlds/ab?X-Amz-Signature=abc&x=1 failed"));
		assertEquals("ticket <token> done", LogScrub.scrub("ticket eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ4In0.sig-part done"));
		assertEquals("eyJ short, no change", LogScrub.scrub("eyJ short, no change"));
		assertEquals("plain line", LogScrub.scrub("plain line"));
	}

	@Test
	void removesPublicAddressesButKeepsHomeOnes() {
		assertEquals("forwarded port 5 (upnp TCP <ip>:32423)", LogScrub.scrub("forwarded port 5 (upnp TCP 101.191.108.7:32423)"));
		assertEquals("joining Sam directly at [<ip>]:51234", LogScrub.scrub("joining Sam directly at [2606:4700::1111]:51234"));
		assertEquals("at 192.168.1.20:51234 and 127.0.0.1:5", LogScrub.scrub("at 192.168.1.20:51234 and 127.0.0.1:5"));
		assertEquals("version 1.21.1 and time 12:45:18", LogScrub.scrub("version 1.21.1 and time 12:45:18"));
	}
}
