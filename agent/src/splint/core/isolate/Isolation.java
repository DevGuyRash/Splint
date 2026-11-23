package splint.core.isolate;

import zombie.iso.IsoMovingObject;

/**
 * The update scheduler's per-object calls, routed here (see CoreModule): each object's
 * preupdate, frameStep, update and postupdate runs on its own, so one object that throws costs
 * only itself, never the rest of the frame. Normal path: one field read and a virtual call.
 */
public final class Isolation {
    private Isolation() {}

    public static void preupdate(IsoMovingObject o) {
        if (Breaker.frozen(o)) return;
        try {
            o.preupdate();
        } catch (VirtualMachineError e) {
            throw e;
        } catch (Throwable t) {
            Breaker.failed(o, "preupdate", t);
        }
    }

    public static void frameStep(IsoMovingObject o) {
        if (Breaker.frozen(o)) return;
        try {
            o.frameStep();
        } catch (VirtualMachineError e) {
            throw e;
        } catch (Throwable t) {
            Breaker.failed(o, "frameStep", t);
        }
    }

    public static void update(IsoMovingObject o) {
        if (Breaker.frozen(o)) return;
        try {
            o.update();
        } catch (VirtualMachineError e) {
            throw e;
        } catch (Throwable t) {
            Breaker.failed(o, "update", t);
        }
    }

    public static void postupdate(IsoMovingObject o) {
        if (Breaker.frozen(o)) return;
        try {
            o.postupdate();
            Breaker.ok(o);
        } catch (VirtualMachineError e) {
            throw e;
        } catch (Throwable t) {
            Breaker.failed(o, "postupdate", t);
        }
    }
}
