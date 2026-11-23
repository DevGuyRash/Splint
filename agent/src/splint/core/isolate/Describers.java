package splint.core.isolate;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import splint.core.world.World;
import zombie.iso.IsoMovingObject;

/**
 * How to name a game object in logs, incidents and command output. Modules register describers
 * for the classes they know (animals: type, ID, hutch, trailer...); anything else gets its class
 * and position. Only used when something went wrong or an admin asks.
 */
public final class Describers {
    /** {@code typeName} is a class name, not a Class: modules register in premain, before game classes may load. */
    public record Describer(String module, String typeName, Function<Object, String> full, Function<Object, String> brief) {}

    private static final CopyOnWriteArrayList<Describer> ALL = new CopyOnWriteArrayList<>();
    private static final ClassValue<Describer> FOR_CLASS = new ClassValue<>() {
        @Override
        protected Describer computeValue(Class<?> c) {
            // The most specific registered class in c's superclass chain wins.
            for (Class<?> k = c; k != null; k = k.getSuperclass()) {
                for (Describer d : ALL) if (d.typeName().equals(k.getName())) return d;
            }
            return null;
        }
    };

    private Describers() {}

    /** Registered from a module's constructor or premain-time setup. */
    public static void register(String module, String className, Function<Object, String> full, Function<Object, String> brief) {
        ALL.add(new Describer(module, className, full, brief));
    }

    /** The module that owns objects of this kind ("core" when none does). */
    public static String module(Object o) {
        Describer d = FOR_CLASS.get(o.getClass());
        return d == null ? "core" : d.module();
    }

    public static String describe(Object o) {
        try {
            Describer d = FOR_CLASS.get(o.getClass());
            if (d != null) return d.full().apply(o);
            if (o instanceof IsoMovingObject m) {
                int x = (int) Math.floor(m.getX()), y = (int) Math.floor(m.getY()), z = (int) Math.floor(m.getZ());
                return o.getClass().getSimpleName() + " at " + World.where(x, y, z) + World.whoIsNear(x, y);
            }
            return String.valueOf(o);
        } catch (Throwable t) {
            return o.getClass().getSimpleName() + " <describe failed: " + t + ">";
        }
    }

    public static String brief(Object o) {
        try {
            Describer d = FOR_CLASS.get(o.getClass());
            return d != null ? d.brief().apply(o) : o.getClass().getSimpleName();
        } catch (Throwable t) {
            return o.getClass().getSimpleName();
        }
    }
}
