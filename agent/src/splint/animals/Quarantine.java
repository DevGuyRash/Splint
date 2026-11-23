package splint.animals;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import splint.core.ObjectSlot;
import splint.core.log.Log;
import zombie.characters.animals.IsoAnimal;

/**
 * Animals whose update keeps throwing.
 *
 * <p>Vanilla lets the exception escape into the world update, which abandons the rest of the
 * frame (every other object, timed actions, player stats, Lua ticks) and retries the same
 * animal next frame, forever. Here the failure is caught and logged with the animal's
 * location. After {@link #FAILURES_TO_FREEZE} failures in a row the animal is frozen: it stays
 * where it is but is not updated, and is retried after a back-off (1 min, doubling, capped at
 * 30 min) in case the cause was transient. Admins list, release or remove frozen animals with
 * {@code /splint animals frozen}.
 *
 * <p>State lives in a field Splint adds to IsoAnimal, so the per-tick checks are one field read
 * whether or not other animals have failed. Main thread only.
 */
final class Quarantine {
    static final int FAILURES_TO_FREEZE = 3;
    private static final long FIRST_FREEZE_MS = 60_000;
    private static final long MAX_FREEZE_MS = 30 * 60_000;

    private static final class State {
        int consecutive;
        long frozenUntil;
        long freezeMs = FIRST_FREEZE_MS;
        String lastError = "";
    }

    private static final class Slot {
        static final ObjectSlot S = ObjectSlot.of(IsoAnimal.class, AnimalsModule.STATE_FIELD);
    }

    /** Animals with state, for listing; only touched on failures and admin commands. */
    private static final Map<IsoAnimal, Boolean> FAILING = new WeakHashMap<>();

    private Quarantine() {}

    static boolean isFrozen(IsoAnimal a) {
        Object s = Slot.S.get(a);
        return s != null && ((State) s).frozenUntil > System.currentTimeMillis();
    }

    static void succeeded(IsoAnimal a) {
        Object s = Slot.S.get(a);
        if (s == null) return;
        if (((State) s).frozenUntil != 0) Log.info("frozen animal updated normally again; releasing: " + AnimalOps.describe(a));
        release(a);
    }

    static void failed(IsoAnimal a, String phase, Throwable t) {
        AnimalStats.UPDATE_FAILURES.incrementAndGet();
        State s = (State) Slot.S.get(a);
        if (s == null) {
            s = new State();
            Slot.S.set(a, s);
            FAILING.put(a, Boolean.TRUE);
        }
        s.consecutive++;
        s.lastError = String.valueOf(t);
        String detail;
        boolean froze = false;
        if (s.consecutive >= FAILURES_TO_FREEZE) {
            s.frozenUntil = System.currentTimeMillis() + s.freezeMs;
            detail = "frozen for " + s.freezeMs / 1000 + "s after " + s.consecutive + " failures in a row";
            s.freezeMs = Math.min(MAX_FREEZE_MS, s.freezeMs * 2);
            s.consecutive = 0;
            froze = true;
        } else {
            detail = "failure " + s.consecutive + " of " + FAILURES_TO_FREEZE + " before freezing";
        }
        String kind = phase + " threw " + t.getClass().getSimpleName() + (froze ? ", frozen" : "");
        String key = "animal-" + phase + ":" + t.getClass().getName() + ":" + firstLine(t.getMessage());
        String d = detail;
        boolean full = Log.problem("ERROR", key, () -> "animal " + phase + " threw " + t + "; contained, world update continues; "
                + d + "; " + AnimalOps.describe(a), froze ? null : t);
        AnimalOps.incident(kind, a, full);
    }

    static List<IsoAnimal> frozen() {
        List<IsoAnimal> out = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (IsoAnimal a : new ArrayList<>(FAILING.keySet())) {
            Object s = Slot.S.get(a);
            if (s != null && ((State) s).frozenUntil > now) out.add(a);
        }
        return out;
    }

    static String lastError(IsoAnimal a) {
        Object s = Slot.S.get(a);
        return s == null ? "" : ((State) s).lastError;
    }

    static void release(IsoAnimal a) {
        Slot.S.set(a, null);
        FAILING.remove(a);
    }

    private static String firstLine(String s) {
        if (s == null) return "";
        int nl = s.indexOf('\n');
        return nl < 0 ? s : s.substring(0, nl);
    }
}
