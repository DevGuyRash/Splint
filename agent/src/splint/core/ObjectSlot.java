package splint.core;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

/**
 * A field Splint adds to a game class (declared by a module as a {@code Patcher.Field}) to hold
 * that module's per-object state. Reading it is a plain field read, so per-object, per-tick
 * checks need no map lookup, hashing or lock. Main-thread access only.
 */
public final class ObjectSlot {
    private final VarHandle handle;

    private ObjectSlot(VarHandle handle) {
        this.handle = handle;
    }

    public static ObjectSlot of(Class<?> owner, String field) {
        try {
            return new ObjectSlot(MethodHandles.publicLookup().findVarHandle(owner, field, Object.class));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Splint field " + owner.getName() + "." + field + " was not added", e);
        }
    }

    public Object get(Object target) {
        return handle.get(target);
    }

    public void set(Object target, Object value) {
        handle.set(target, value);
    }
}
