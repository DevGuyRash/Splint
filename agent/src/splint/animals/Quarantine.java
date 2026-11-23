package splint.animals;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import splint.core.log.Log;
import zombie.characters.animals.IsoAnimal;

/**
 * Animals whose update keeps throwing.
 *
 * <p>Vanilla lets the exception escape into the world update, which abandons the rest of the
 * frame (every other object, timed actions, player stats, Lua ticks) and retries the same
 * animal next frame, forever. Here the failure is caught and logged with the animal's
 * location. After {@link #FAILURES_TO_FREEZE} failures in a row the animal is frozen: it
 * stays where it is but is not updated, and it is retried after a back-off (1 min, doubling,
 * capped at 30 min) in case the cause was transient. Admins can list, release or kill frozen
 * animals with {@code /splint animals frozen}.
 */
public final class Quarantine {
    static final int FAILURES_TO_FREEZE = 3;
    private static final long FIRST_FREEZE_MS = 60_000;
    private static final long MAX_FREEZE_MS = 30 * 60_000;

    private static final class State {
        int consecutive;
        long frozenUntil;
        long freezeMs = FIRST_FREEZE_MS;
        String lastError = "";
    }

    private static final Map<IsoAnimal, State> STATES = new WeakHashMap<>();
    /** Fast path for the per-tick checks: no lock and no map lookup while nothing has failed. */
    private static volatile boolean any;

    private Quarantine() {}

    public static boolean isFrozen(IsoAnimal a) {
        if (!any) return false;
        synchronized (Quarantine.class) {
            State s = STATES.get(a);
            return s != null && s.frozenUntil > System.currentTimeMillis();
        }
    }

    public static void succeeded(IsoAnimal a) {
        if (!any) return;
        synchronized (Quarantine.class) {
            succeededLocked(a);
            any = !STATES.isEmpty();
        }
    }

    private static void succeededLocked(IsoAnimal a) {
        State s = STATES.get(a);
        if (s != null && s.frozenUntil == 0) STATES.remove(a);
        else if (s != null) {
            Log.info("frozen animal updated normally again; releasing: " + AnimalOps.describe(a));
            STATES.remove(a);
        }
    }

    public static void failed(IsoAnimal a, String phase, Throwable t) {
        AnimalStats.UPDATE_FAILURES.incrementAndGet();
        String detail;
        boolean froze = false;
        synchronized (Quarantine.class) {
            State s = STATES.computeIfAbsent(a, k -> new State());
            any = true;
            s.consecutive++;
            s.lastError = String.valueOf(t);
            if (s.consecutive >= FAILURES_TO_FREEZE) {
                s.frozenUntil = System.currentTimeMillis() + s.freezeMs;
                detail = "frozen for " + s.freezeMs / 1000 + "s after " + s.consecutive + " failures in a row";
                s.freezeMs = Math.min(MAX_FREEZE_MS, s.freezeMs * 2);
                s.consecutive = 0;
                froze = true;
            } else {
                detail = "failure " + s.consecutive + " of " + FAILURES_TO_FREEZE + " before freezing";
            }
        }
        AnimalOps.incident(phase + " threw " + t.getClass().getSimpleName() + (froze ? ", frozen" : ""), a);
        String key = "animal-" + phase + ":" + t.getClass().getName() + ":" + firstLine(t.getMessage());
        Log.problem("ERROR", key, "animal " + phase + " threw " + t + "; contained, world update continues; " + detail
                + "; " + AnimalOps.describe(a), froze ? null : t);
    }

    public static synchronized List<IsoAnimal> frozen() {
        List<IsoAnimal> out = new ArrayList<>();
        long now = System.currentTimeMillis();
        STATES.forEach((a, s) -> { if (s.frozenUntil > now) out.add(a); });
        return out;
    }

    public static synchronized String lastError(IsoAnimal a) {
        State s = STATES.get(a);
        return s == null ? "" : s.lastError;
    }

    public static synchronized void release(IsoAnimal a) {
        STATES.remove(a);
        any = !STATES.isEmpty();
    }

    private static String firstLine(String s) {
        if (s == null) return "";
        int nl = s.indexOf('\n');
        return nl < 0 ? s : s.substring(0, nl);
    }
}
