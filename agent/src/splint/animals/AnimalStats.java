package splint.animals;

import java.util.concurrent.atomic.AtomicLong;
import splint.core.Counters;

/** The animals module's counters (shown by /splint status). */
final class AnimalStats {
    static final AtomicLong IDS_ASSIGNED = c("idsAssigned");
    static final AtomicLong DUPLICATE_IDS_FIXED = c("duplicateIdsFixed");
    static final AtomicLong DUPLICATE_IDS_PREVENTED = c("duplicateIdsPrevented");
    static final AtomicLong DUPLICATE_COPIES_REMOVED = c("duplicateCopiesRemoved");
    static final AtomicLong GHOSTS_PREVENTED = c("ghostsPrevented");
    static final AtomicLong GHOSTS_REMOVED = c("ghostsRemoved");
    static final AtomicLong WATER_RELOCATIONS = c("waterRelocations");
    static final AtomicLong REMOVAL_FAILURES = c("removalFailuresContained");
    static final AtomicLong HUTCH_FAILURES = c("hutchFailuresContained");
    static final AtomicLong BREEDS_RESTORED = c("breedsRestored");
    static final AtomicLong UNSAVEABLE = c("unsaveableAnimalsSkipped");
    static final AtomicLong KILLED_BY_COMMAND = c("killedByCommand");

    private AnimalStats() {}

    private static AtomicLong c(String name) {
        return Counters.of(AnimalsModule.ID, name);
    }
}
