package splint.core;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.StringJoiner;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Named counters per module, reported by {@code /splint status} and the periodic summary.
 * Modules keep the returned {@link AtomicLong}s in static final fields, so counting on a hot
 * path is a single atomic increment.
 */
public final class Counters {
    private static final Map<String, Map<String, AtomicLong>> ALL = new LinkedHashMap<>();

    private Counters() {}

    public static synchronized AtomicLong of(String module, String name) {
        return ALL.computeIfAbsent(module, k -> new LinkedHashMap<>()).computeIfAbsent(name, k -> new AtomicLong());
    }

    /** "animals: idsAssigned=12 ghostsRemoved=0 ..." per module, one line each. */
    public static synchronized String summary() {
        StringJoiner lines = new StringJoiner("\n");
        ALL.forEach((module, counters) -> {
            StringJoiner line = new StringJoiner(" ", module + ": ", "");
            counters.forEach((name, value) -> line.add(name + "=" + value.get()));
            lines.add(line.toString());
        });
        return lines.toString();
    }
}
