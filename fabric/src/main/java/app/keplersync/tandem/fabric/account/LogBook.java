package app.keplersync.tandem.fabric.account;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;

/**
 * What Send logs sends: Tandem's own log lines and every warning or error from the game, since it started, newest
 * kept when it gets long. Kept in memory only; it leaves the PC when the player presses Send logs, or by itself after a
 * warning ({@link AutoLogs}) unless Settings turns that off.
 */
public final class LogBook extends AbstractAppender {
	private static final int MAX_CHARS = 300_000;
	private static final int MAX_TRACE_LINES = 40;
	private static final LogBook INSTANCE = new LogBook();

	private final ArrayDeque<String> lines = new ArrayDeque<>();
	private int chars;
	private final SimpleDateFormat time = new SimpleDateFormat("HH:mm:ss");

	private LogBook() {
		super("TandemLogBook", null, null, true, Property.EMPTY_ARRAY);
	}

	public static void install() {
		INSTANCE.start();
		((Logger) LogManager.getRootLogger()).addAppender(INSTANCE);
	}

	@Override
	public void append(LogEvent event) {
		String logger = event.getLoggerName() == null ? "" : event.getLoggerName();
		boolean ours = logger.startsWith("app.keplersync.tandem");
		if (!ours && !event.getLevel().isMoreSpecificThan(Level.WARN)) return;
		if (ours && event.getLevel().isMoreSpecificThan(Level.WARN)) AutoLogs.trouble(event.getMessage().getFormattedMessage(), event.getThrown());
		StringBuilder b = new StringBuilder();
		synchronized (this) {
			b.append('[').append(time.format(new Date(event.getTimeMillis()))).append("] [")
					.append(event.getThreadName()).append('/').append(event.getLevel()).append("] (")
					.append(logger.substring(logger.lastIndexOf('.') + 1)).append(") ")
					.append(event.getMessage().getFormattedMessage());
		}
		if (event.getThrown() != null) {
			StringWriter trace = new StringWriter();
			event.getThrown().printStackTrace(new PrintWriter(trace));
			String[] t = trace.toString().split("\\R");
			for (int i = 0; i < Math.min(t.length, MAX_TRACE_LINES); i++) b.append('\n').append(t[i]);
			if (t.length > MAX_TRACE_LINES) b.append("\n\t... ").append(t.length - MAX_TRACE_LINES).append(" more");
		}
		add(b.toString());
	}

	private synchronized void add(String line) {
		lines.addLast(line);
		chars += line.length() + 1;
		while (chars > MAX_CHARS && lines.size() > 1) chars -= lines.removeFirst().length() + 1;
	}

	/** Everything kept so far, oldest first. */
	public static String text() {
		synchronized (INSTANCE) {
			return String.join("\n", INSTANCE.lines);
		}
	}
}
