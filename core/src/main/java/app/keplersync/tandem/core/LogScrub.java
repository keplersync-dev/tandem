package app.keplersync.tandem.core;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What a sent log doesn't need, removed before it leaves the PC: the user name in home folder paths
 * ({@code C:\Users\<user>\}, {@code /Users/<user>/}, {@code /home/<user>/}), the query string of links (presigned
 * links carry their signature there), tokens ({@code eyJ…}, how every sign-in token and relay ticket starts), and
 * public IP addresses (a friend's, or this PC's, when games connect directly). Addresses on home networks stay: they
 * say nothing about who someone is. The server does the same for logs from older versions.
 */
public final class LogScrub {
	private LogScrub() {
	}

	private static final String[] HOMES = {"\\users\\", "/users/", "/home/"};

	/** IPv4 (a port may follow) or IPv6 (bare, or in brackets). */
	private static final Pattern IP = Pattern.compile(
			"(?<![\\w.:])(?:((?:\\d{1,3}\\.){3}\\d{1,3})(?![\\w.])|([0-9A-Fa-f]{0,4}(?::[0-9A-Fa-f]{0,4}){2,7})(?![\\w.:]))");

	public static String scrub(String text) {
		if (text == null) return null;
		return addresses(paths(text));
	}

	/** Public IP addresses become {@code <ip>}. */
	private static String addresses(String text) {
		Matcher m = IP.matcher(text);
		StringBuffer out = new StringBuffer(text.length());
		while (m.find()) {
			String ip = m.group(1) != null ? m.group(1) : m.group(2);
			m.appendReplacement(out, Addresses.isPublic(ip) ? "<ip>" : Matcher.quoteReplacement(ip));
		}
		m.appendTail(out);
		return out.toString();
	}

	private static String paths(String text) {
		// Lowercasing ASCII keeps every character where it was, so positions in `lower` are positions in `text`.
		String lower = text.toLowerCase(Locale.ROOT);
		StringBuilder out = new StringBuilder(text.length());
		int i = 0;
		while (i < text.length()) {
			int home = -1, marker = 0;
			for (String m : HOMES) {
				int at = lower.indexOf(m, i);
				if (at >= 0 && (home < 0 || at < home)) {
					home = at;
					marker = m.length();
				}
			}
			int link = first(lower.indexOf("http://", i), lower.indexOf("https://", i));
			int token = text.indexOf("eyJ", i);
			int at = first(first(home, link), token);
			if (at < 0) break;
			out.append(text, i, at);
			if (at == home) {
				// Names can have spaces ("Alex Kim"), so the name runs to the next separator or the line's end.
				int end = endAt(text, at + marker, "\\/\"'\n\r;,");
				out.append(text, at, at + marker).append("<user>");
				i = end;
			} else if (at == link) {
				int end = at;
				while (end < text.length() && !Character.isWhitespace(text.charAt(end)) && "\"'<>)".indexOf(text.charAt(end)) < 0) end++;
				String url = text.substring(at, end);
				int q = url.indexOf('?');
				out.append(q < 0 ? url : url.substring(0, q) + "?<removed>");
				i = end;
			} else {
				int end = at;
				while (end < text.length() && (Character.isLetterOrDigit(text.charAt(end)) || "-_.".indexOf(text.charAt(end)) >= 0)) end++;
				out.append(end - at >= 20 ? "<token>" : text.substring(at, end));
				i = end;
			}
		}
		if (i < text.length()) out.append(text, i, text.length());
		return out.toString();
	}

	private static int first(int a, int b) {
		return a < 0 ? b : b < 0 ? a : Math.min(a, b);
	}

	private static int endAt(String text, int from, String stops) {
		int i = from;
		while (i < text.length() && stops.indexOf(text.charAt(i)) < 0) i++;
		return i;
	}
}
