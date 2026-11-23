package splint.core;

import java.lang.invoke.MethodHandle;
import splint.core.patch.Originals;
import zombie.iso.IsoWorld;

/** Hooks for {@link CoreModule}. */
public final class CoreHooks {
    private static final class WorldUpdate { static final MethodHandle H = Originals.virtual(IsoWorld.class, "update", void.class); }

    private CoreHooks() {}

    /** The heartbeat runs before the world update, so it still runs if that update throws. */
    public static void worldUpdate(IsoWorld world) throws Throwable {
        Splint.tick();
        WorldUpdate.H.invokeExact(world);
    }
}
