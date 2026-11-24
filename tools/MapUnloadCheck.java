import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;

/**
 * Behaviour check for the map cell isolation, run by build.py --verify in the game's own JRE with
 * the agent attached: a real server map cell (ServerMap$ServerCell) whose 64 chunks all throw when
 * removed (bare instances, every field null) is unloaded with the real Unload method.
 *
 * Vanilla throws from the first chunk and keeps the other 63. With Splint every chunk is handed to
 * the save queue and leaves the cell. Exits non-zero when that does not hold.
 */
public final class MapUnloadCheck {
    public static void main(String[] args) throws Exception {
        Object rand = Class.forName("zombie.core.random.RandStandard").getField("INSTANCE").get(null);
        rand.getClass().getMethod("init").invoke(rand);        // static initialisers below draw random numbers
        Class<?> cellClass = Class.forName("zombie.network.ServerMap$ServerCell");
        Class<?> chunkClass = Class.forName("zombie.iso.IsoChunk");
        Field unsafeField = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        Object unsafe = unsafeField.get(null);
        Method allocate = unsafe.getClass().getMethod("allocateInstance", Class.class);
        Object cell = allocate.invoke(unsafe, cellClass);
        Object chunks = Array.newInstance(chunkClass, 8, 8);
        for (int x = 0; x < 8; x++) {
            for (int y = 0; y < 8; y++) {
                Object chunk = allocate.invoke(unsafe, chunkClass);
                set(chunk, "vehicles", new ArrayList<>());     // Unload saves the chunk's vehicles after removing it
                Array.set(Array.get(chunks, x), y, chunk);
            }
        }
        set(cell, "chunks", chunks);
        set(cell, "unexploredRooms", new HashSet<>());
        try {
            cellClass.getMethod("Unload").invoke(cell);
        } catch (java.lang.reflect.InvocationTargetException e) {
            System.out.println("map unload check: FAILED, the unload threw " + e.getCause());
            e.getCause().printStackTrace(System.out);
            System.exit(1);
        }
        int left = 0;
        for (int x = 0; x < 8; x++) for (int y = 0; y < 8; y++) if (Array.get(Array.get(chunks, x), y) != null) left++;
        System.out.println(left == 0 ? "map unload check: a cell whose 64 chunks all failed to unload was emptied"
                : "map unload check: FAILED, " + left + " chunks are still in the cell");
        System.exit(left == 0 ? 0 : 1);
    }

    private static void set(Object target, String name, Object value) throws ReflectiveOperationException {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }
}
