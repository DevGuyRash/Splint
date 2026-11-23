package splint.core.log;

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
    /**
     * The process's real standard output. The game replaces System.out with its own logger,
     * which is not safe to call from inside class loading (where the agent's transformer runs).
     */
    private static final java.io.PrintStream BOOT = new java.io.PrintStream(
            new java.io.FileOutputStream(java.io.FileDescriptor.out), true);

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
     * Logs a problem that may repeat: ERROR when an exception was contained, WARN when Splint
     * found and repaired a bad state. The message is only built when it will be written, so
     * callers can describe the object involved without paying for it on suppressed repeats.
     * Returns true when this occurrence was written in full (the first few per key).
     */
    public static boolean problem(String key, java.util.function.Supplier<String> message, Throwable t) {
        return problem(t != null ? "ERROR" : "WARN", key, message, t);
    }

    public static boolean problem(String key, String message, Throwable t) {
        return problem(t != null ? "ERROR" : "WARN", key, () -> message, t);
    }

    public static boolean problem(String level, String key, java.util.function.Supplier<String> message, Throwable t) {
        Counter c = COUNTERS.computeIfAbsent(stableKey(key), k -> new Counter());
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
            write(level, message.get() + " [occurrence " + total + "]", t);
            if (total == FULL_REPORTS_PER_KEY) write("WARN", "further '" + key + "' reports are summarised once a minute", null);
        } else if (summary) {
            write(level, message.get() + " [" + suppressed + " more since last summary, " + total + " total]", null);
        }
        return full;
    }

    /** Digits (indices, coordinates, IDs) would make every occurrence its own key. */
    static String stableKey(String key) {
        StringBuilder b = null;
        for (int i = 0; i < key.length(); i++) {
            char ch = key.charAt(i);
            if (ch >= '0' && ch <= '9') {
                if (b == null) b = new StringBuilder(key.length()).append(key, 0, i);
                if (b.length() == 0 || b.charAt(b.length() - 1) != '#') b.append('#');
            } else if (b != null) {
                b.append(ch);
            }
        }
        return b == null ? key : b.toString();
    }

    /** For the transformer: never touches game code, never throws. */
    public static void boot(String message, Throwable t) {
        try {
            synchronized (BOOT) {
                BOOT.println("[Splint] " + (t == null ? "INFO " : "ERROR ") + message);
                if (t != null) BOOT.println(stack(t));
            }
        } catch (Throwable ignored) {
            // nothing left to report to
        }
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
        synchronized (BOOT) {
            BOOT.println(line);
            if (trace != null) BOOT.println(trace);
        }
    }

    public static String stack(Throwable t) {
        StringWriter w = new StringWriter();
        t.printStackTrace(new PrintWriter(w));
        return w.toString().stripTrailing();
    }
}
