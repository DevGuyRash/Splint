package splint.animals;

import java.util.concurrent.atomic.AtomicLong;

/** Counters reported by {@code /splint status}; reset on restart. */
public final class Stats {
    public static final AtomicLong idsAssigned = new AtomicLong();
    public static final AtomicLong duplicateIdsFixed = new AtomicLong();
    public static final AtomicLong ghostsRemoved = new AtomicLong();
    public static final AtomicLong ghostsPrevented = new AtomicLong();
    public static final AtomicLong waterRelocations = new AtomicLong();
    public static final AtomicLong updateFailures = new AtomicLong();
    public static final AtomicLong removalFailures = new AtomicLong();
    public static final AtomicLong hutchFailures = new AtomicLong();
    public static final AtomicLong killedByCommand = new AtomicLong();

    private Stats() {}

    public static String summary() {
        return "idsAssigned=" + idsAssigned + " duplicateIdsFixed=" + duplicateIdsFixed
                + " ghostsPrevented=" + ghostsPrevented + " ghostsRemoved=" + ghostsRemoved
                + " waterRelocations=" + waterRelocations + " updateFailuresContained=" + updateFailures
                + " removalFailuresContained=" + removalFailures + " hutchFailuresContained=" + hutchFailures
                + " killedByCommand=" + killedByCommand;
    }
}
