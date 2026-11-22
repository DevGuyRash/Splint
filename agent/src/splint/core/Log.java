package splint.core;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Splint's log. Every line goes to the server console (console.txt / the hosting panel) with
 * a {@code [Splint]} prefix, and, once the game's logging is up, to {@code Logs/*_Splint.txt}
 * next to the game's other logs.
 *
 * <p>Repeating problems are rate limited per key: the first occurrences are logged in full
 * with a stack trace, then a one-line summary with the running count once a minute, so a
 * broken animal can never flood the log the way the vanilla per-frame exception does.
 */
public final class Log {
    private static final int FULL_REPORTS_PER_KEY = 3;
    private static final long SUMMARY_INTERVAL_MS = 60_000;
    private static volatile boolean gameLogging;
    private static final Map<String, Counter> COUNTERS = new ConcurrentHashMap<>();

    private static final class Counter {
        long total;
        long suppressed;
        long lastSummary;
    }

    private Log() {}

    /** Called from the first hook that runs inside the game loop, when the game's loggers exist. */
    public static void gameStarted() {
        gameLogging = true;
    }

    public static void info(String message) { write("INFO", message, null); }

    public static void warn(String message) { write("WARN", message, null); }

    public static void error(String message, Throwable t) { write("ERROR", message, t); }

    /**
     * Logs a problem that may repeat. Returns the number of times {@code key} has been seen,
     * including this one.
     */
    public static long problem(String key, String message, Throwable t) {
        Counter c = COUNTERS.computeIfAbsent(key, k -> new Counter());
        long now = System.currentTimeMillis();
        long total;
        boolean full;
        boolean summary = false;
        long suppressed = 0;
        synchronized (c) {
            total = ++c.total;
            full = total <= FULL_REPORTS_PER_KEY;
            if (!full) {
                c.suppressed++;
                if (now - c.lastSummary >= SUMMARY_INTERVAL_MS) {
                    summary = true;
                    suppressed = c.suppressed;
                    c.suppressed = 0;
                    c.lastSummary = now;
                }
            } else {
                c.lastSummary = now;
            }
        }
        if (full) {
            write("ERROR", message + " [occurrence " + total + "]", t);
            if (total == FULL_REPORTS_PER_KEY) write("WARN", "further '" + key + "' reports are summarised once a minute", null);
        } else if (summary) {
            write("ERROR", message + " [" + suppressed + " more since last summary, " + total + " total]", null);
        }
        return total;
    }

    private static void write(String level, String message, Throwable t) {
        String line = "[Splint] " + level + " " + message;
        String trace = t == null ? null : stack(t);
        if (gameLogging) {
            try {
                zombie.debug.DebugLog.log(trace == null ? line : line + "\n" + trace);
                zombie.core.logger.LoggerManager.getLogger("Splint").write(trace == null ? level + " " + message
                        : level + " " + message + "\n" + trace);
                return;
            } catch (Throwable ignored) {
                // fall through to stdout
            }
        }
        System.out.println(line);
        if (trace != null) System.out.println(trace);
    }

    public static String stack(Throwable t) {
        StringWriter w = new StringWriter();
        t.printStackTrace(new PrintWriter(w));
        return w.toString().stripTrailing();
    }
}
