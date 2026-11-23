package splint.core.isolate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicLong;
import splint.core.Counters;
import splint.core.ObjectSlot;
import splint.core.log.Incidents;
import splint.core.log.Log;
import zombie.iso.IsoMovingObject;

/**
 * A per-object circuit breaker for game objects whose updates keep throwing.
 *
 * <p>Vanilla lets an exception from one object escape into the world update, which abandons the
 * rest of the frame (every other object, timed actions, player stats, Lua ticks) and retries the
 * same object next frame, forever. With Splint the failure is caught, logged with the object's
 * location and the players near it, and the world carries on. After {@link #FAILURES_TO_FREEZE}
 * failures in a row the object is frozen: it stays where it is but is not updated, and it is
 * retried after a back-off (1 min, doubling, capped at 30 min). Admins list and release frozen
 * objects with {@code /splint frozen}.
 *
 * <p>State lives in a field Splint adds to IsoMovingObject, so {@link #frozen} costs one field
 * read whether or not anything has ever failed. Main thread only.
 */
public final class Breaker {
    public static final String FIELD = "splint$breaker";
    static final int FAILURES_TO_FREEZE = 3;
    private static final long FIRST_FREEZE_MS = 60_000;
    private static final long MAX_FREEZE_MS = 30 * 60_000;
    private static final AtomicLong CONTAINED = Counters.of("core", "failuresContained");
    private static final AtomicLong FROZE = Counters.of("core", "objectsFrozen");

    /** World-update counter, advanced by the heartbeat; failures remember the frame they happened in. */
    private static long frame;

    public static void nextFrame() { frame++; }

    private static final class State {
        long lastFailFrame = -1;
        int consecutive;
        long frozenUntil;
        long freezeMs = FIRST_FREEZE_MS;
        String lastError = "";
    }

    private static final class Slot {
        static final ObjectSlot S = ObjectSlot.of(IsoMovingObject.class, FIELD);
    }

    /** Objects with state, for listing; only touched on failures and admin commands. */
    private static final Map<IsoMovingObject, Boolean> TRACKED = new WeakHashMap<>();

    private Breaker() {}

    /** True while {@code o} is frozen. One field read on the normal path. */
    public static boolean frozen(IsoMovingObject o) {
        Object s = Slot.S.get(o);
        return s != null && ((State) s).frozenUntil > System.currentTimeMillis();
    }

    /**
     * {@code o} finished a frame: if nothing failed in this frame, forget earlier failures. One
     * field read on the normal path.
     */
    public static void ok(IsoMovingObject o) {
        Object s = Slot.S.get(o);
        if (s == null) return;
        State st = (State) s;
        if (st.lastFailFrame == frame) return;
        if (st.frozenUntil != 0) Log.info("frozen object updated normally again; releasing: " + Describers.describe(o));
        release(o);
    }

    /** Records a contained failure of {@code o} in {@code phase}; freezes it on repeats. */
    public static void failed(IsoMovingObject o, String phase, Throwable t) {
        CONTAINED.incrementAndGet();
        State s = (State) Slot.S.get(o);
        if (s == null) {
            s = new State();
            Slot.S.set(o, s);
            TRACKED.put(o, Boolean.TRUE);
        }
        s.lastFailFrame = frame;
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
            FROZE.incrementAndGet();
        } else {
            detail = "failure " + s.consecutive + " of " + FAILURES_TO_FREEZE + " before freezing";
        }
        String module = Describers.module(o);
        String what = o.getClass().getSimpleName();
        String key = module + ":" + what + "-" + phase + ":" + t.getClass().getName() + ":" + firstLine(t.getMessage());
        String d = detail;
        boolean full = Log.problem("ERROR", key, () -> what + " " + phase + " threw " + t + "; contained, world update continues; "
                + d + "; " + Describers.describe(o), froze ? null : t);
        String kind = what + " " + phase + " threw " + t.getClass().getSimpleName() + (froze ? ", frozen" : "");
        int x = (int) Math.floor(o.getX()), y = (int) Math.floor(o.getY());
        if (full) Incidents.record(module, kind, x, y, (int) Math.floor(o.getZ()), Describers.brief(o));
        else Incidents.count(module, kind, x, y);
    }

    public static List<IsoMovingObject> frozenObjects() {
        List<IsoMovingObject> out = new ArrayList<>();
        for (IsoMovingObject o : new ArrayList<>(TRACKED.keySet())) if (frozen(o)) out.add(o);
        return out;
    }

    public static String lastError(IsoMovingObject o) {
        Object s = Slot.S.get(o);
        return s == null ? "" : ((State) s).lastError;
    }

    public static void release(IsoMovingObject o) {
        Slot.S.set(o, null);
        TRACKED.remove(o);
    }

    private static String firstLine(String s) {
        if (s == null) return "";
        int nl = s.indexOf('\n');
        return nl < 0 ? s : s.substring(0, nl);
    }
}
