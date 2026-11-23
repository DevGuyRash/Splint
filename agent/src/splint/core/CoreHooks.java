package splint.core;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import splint.core.patch.Patcher;
import zombie.iso.IsoWorld;

/** Hooks for {@link CoreModule}. */
public final class CoreHooks {
    private static final class WorldUpdate {
        static final MethodHandle H;
        static {
            try {
                H = MethodHandles.publicLookup().findVirtual(IsoWorld.class, Patcher.ORIGINAL_PREFIX + "update",
                        MethodType.methodType(void.class));
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    private CoreHooks() {}

    /** The heartbeat runs before the world update, so it still runs if that update throws. */
    public static void worldUpdate(IsoWorld world) throws Throwable {
        Splint.tick();
        WorldUpdate.H.invokeExact(world);
    }
}
