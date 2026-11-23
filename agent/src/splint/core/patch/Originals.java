package splint.core.patch;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * Method handles to the original bodies {@link Patcher} keeps as {@code splint$<name>}. Hooks
 * resolve them in a per-hook holder class ({@code static final MethodHandle H = ...}), so a
 * patch that failed to apply only affects its own hook, and the JIT inlines the call.
 */
public final class Originals {
    private Originals() {}

    public static MethodHandle virtual(Class<?> owner, String name, Class<?> ret, Class<?>... params) {
        try {
            MethodHandles.Lookup lookup = java.lang.reflect.Modifier.isPublic(owner.getModifiers())
                    ? MethodHandles.publicLookup() : MethodHandles.privateLookupIn(owner, MethodHandles.lookup());
            return lookup.findVirtual(owner, Patcher.ORIGINAL_PREFIX + name, MethodType.methodType(ret, params));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Splint patch for " + owner.getName() + "." + name + " is not applied", e);
        }
    }

    /** For package-private game classes that hook code cannot name. */
    public static MethodHandle virtual(String owner, String name, Class<?> ret, Class<?>... params) {
        try {
            return virtual(Class.forName(owner), name, ret, params);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(owner + " not found", e);
        }
    }

    public static MethodHandle statik(Class<?> owner, String name, Class<?> ret, Class<?>... params) {
        try {
            return MethodHandles.publicLookup().findStatic(owner, Patcher.ORIGINAL_PREFIX + name, MethodType.methodType(ret, params));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Splint patch for " + owner.getName() + "." + name + " is not applied", e);
        }
    }
}
