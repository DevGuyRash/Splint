package splint.core.isolate;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicLong;
import splint.core.Counters;
import splint.core.Threads;
import splint.core.log.Incidents;
import splint.core.log.Log;
import zombie.iso.IsoMovingObject;

/**
 * A per-object circuit breaker for game objects whose updates throw.
 *
 * <p>Vanilla lets an exception from one object escape into the world update, which abandons the
 * rest of the frame (every other object, timed actions, player stats, Lua ticks) and retries the
 * same object next frame, forever. With Splint the failure is caught and logged with the
 * object's location and the players near it, and the world carries on. Like vanilla's own
 * abort, the object's remaining phases are skipped for that frame.
 *
 * <p>Objects of a type a module opted in (see {@link Describers#register}) are frozen after
 * {@link #FAILURES_TO_FREEZE} failing frames in a row: left in place, not updated, retried after
 * a back-off (1 min, doubling up to 30 min, remembered for 10 min of clean running). Other
 * objects, players and vehicles among them, are never frozen, because the server must keep
 * tracking them; their failures are only contained and logged. Admins list and release frozen
 * objects with {@code /splint frozen}.
 *
 * <p>State lives in a field Splint adds to IsoMovingObject. While an object has never failed,
 * every check is one field read through a constant VarHandle. Main thread only; failures seen
 * on another thread are handed to it.
 */
public final class Breaker {
    public static final String FIELD = "splint$breaker";
    static final int FAILURES_TO_FREEZE = 3;
    private static final long FIRST_FREEZE_MS = 60_000;
    private static final long MAX_FREEZE_MS = 30 * 60_000;
    /** Clean running after which an object's history is forgotten. */
    private static final long FORGET_MS = 10 * 60_000;
    private static final AtomicLong CONTAINED = Counters.of("core", "failuresContained");
    private static final AtomicLong FROZE = Counters.of("core", "objectsFrozen");

    private static final class State {
        long lastFailFrame = -1;
        long lastFailMs;
        int failingFrames;
        long frozenUntil;
        long freezeMs = FIRST_FREEZE_MS;
        String lastError = "";
    }

    /** Resolved on first use (after the field was added), constant for the JIT, exact types. */
    private static final class Slot {
        static final VarHandle VH;
        static {
            try {
                VH = MethodHandles.publicLookup().findVarHandle(IsoMovingObject.class, FIELD, Object.class)
                        .withInvokeExactBehavior();
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Splint field IsoMovingObject." + FIELD + " was not added", e);
            }
        }
    }

    /** World-update counter, advanced by the heartbeat. */
    private static long frame;

    /** Objects with state, for listing; only touched on failures and admin commands. */
    private static final Map<IsoMovingObject, Boolean> TRACKED = new WeakHashMap<>();

    private Breaker() {}

    public static void nextFrame() { frame++; }

    private static State state(IsoMovingObject o) {
        return (State) (Object) Slot.VH.get(o);
    }

    /**
     * True while {@code o} must not run: frozen, or it already failed earlier this frame. One
     * field read while it has never failed.
     */
    public static boolean frozen(IsoMovingObject o) {
        State s = state(o);
        return s != null && (s.lastFailFrame == frame || s.frozenUntil > System.currentTimeMillis());
    }

    /** {@code o} finished a frame without failing. One field read while it has never failed. */
    public static void ok(IsoMovingObject o) {
        State s = state(o);
        if (s == null || s.lastFailFrame == frame || !Threads.isMain()) return;
        long now = System.currentTimeMillis();
        if (now - s.lastFailMs > FORGET_MS) {
            release(o);
            return;
        }
        s.failingFrames = 0;
        if (s.frozenUntil != 0) {
            s.frozenUntil = 0;                       // thawed and clean; keep the back-off history
            Log.problem("WARN", "breaker-thaw", () -> "frozen object runs normally again: " + Describers.describe(o), null);
        }
    }

    /** Records a contained failure of {@code o} in {@code phase}. */
    public static void failed(IsoMovingObject o, String phase, Throwable t) {
        if (!Threads.isMain()) {
            Threads.onMain(() -> failed(o, phase, t));
            return;
        }
        CONTAINED.incrementAndGet();
        State s = state(o);
        if (s == null) {
            s = new State();
            Slot.VH.set(o, (Object) s);
            TRACKED.put(o, Boolean.TRUE);
        }
        s.lastError = String.valueOf(t);
        if (s.lastFailFrame == frame) return;        // one failing frame counts once
        s.lastFailFrame = frame;
        s.lastFailMs = System.currentTimeMillis();
        s.failingFrames++;
        boolean freezable = Describers.freezable(o);
        String detail;
        boolean froze = false;
        if (freezable && s.failingFrames >= FAILURES_TO_FREEZE) {
            s.frozenUntil = s.lastFailMs + s.freezeMs;
            detail = "frozen for " + s.freezeMs / 1000 + "s after " + s.failingFrames + " failing frames in a row";
            s.freezeMs = Math.min(MAX_FREEZE_MS, s.freezeMs * 2);
            s.failingFrames = 0;
            froze = true;
            FROZE.incrementAndGet();
        } else {
            detail = freezable ? "failing frame " + s.failingFrames + " of " + FAILURES_TO_FREEZE + " before freezing"
                    : "never frozen (the server must keep updating this kind of object)";
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
        long now = System.currentTimeMillis();
        for (IsoMovingObject o : new ArrayList<>(TRACKED.keySet())) {
            State s = state(o);
            if (s != null && s.frozenUntil > now) out.add(o);
        }
        return out;
    }

    public static String lastError(IsoMovingObject o) {
        State s = state(o);
        return s == null ? "" : s.lastError;
    }

    public static void release(IsoMovingObject o) {
        Slot.VH.set(o, (Object) null);
        TRACKED.remove(o);
    }

    private static String firstLine(String s) {
        if (s == null) return "";
        int nl = s.indexOf('\n');
        return nl < 0 ? s : s.substring(0, nl);
    }
}
