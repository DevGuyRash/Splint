import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.ArrayList;

/**
 * Behaviour check for the animal-population save guard, run by build.py --verify in the game's
 * own JRE with the agent attached: a real herd (VirtualAnimal) holding one half-built IsoAnimal
 * (no type, no data, as vanilla leaves one on a water square) is saved with the real save method.
 *
 * Vanilla throws a NullPointerException from IsoAnimal.save, which aborts the whole save. With
 * Splint the half-built animal is dropped before the herd is counted, the save completes and the
 * herd is written with zero animals. Exits non-zero when that does not hold.
 */
public final class HerdSaveCheck {
    public static void main(String[] args) throws Exception {
        Class<?> herdClass = Class.forName("zombie.characters.animals.VirtualAnimal");
        Class<?> animalClass = Class.forName("zombie.characters.animals.IsoAnimal");
        // Bare instances, so no running world (or its random-number setup) is needed. A bare
        // IsoAnimal has every field null, which is exactly what a half-built animal looks like to
        // the save: no adef, no data, no type. The herd gets the fields its save reads.
        Field unsafeField = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        Object unsafe = unsafeField.get(null);
        Method allocate = unsafe.getClass().getMethod("allocateInstance", Class.class);
        Object herd = allocate.invoke(unsafe, herdClass);
        Object ghost = allocate.invoke(unsafe, animalClass);
        ArrayList<Object> list = new ArrayList<>();
        list.add(ghost);
        set(herd, "animals", list);
        set(herd, "forwardDirection", Class.forName("org.joml.Vector2f").getDeclaredConstructor().newInstance());
        set(herd, "migrationGroup", "");
        Method save = herdClass.getDeclaredMethod("save", ByteBuffer.class);
        save.setAccessible(true);
        ByteBuffer out = ByteBuffer.allocate(1 << 16);
        try {
            save.invoke(herd, out);
        } catch (java.lang.reflect.InvocationTargetException e) {
            System.out.println("herd save check: FAILED, the save threw " + e.getCause());
            System.exit(1);
        }
        if (!list.isEmpty()) {
            System.out.println("herd save check: FAILED, the half-built animal is still in the herd");
            System.exit(1);
        }
        System.out.println("herd save check: a herd holding a half-built animal saved cleanly (" + out.position()
                + " bytes, 0 animals)");
    }

    private static void set(Object target, String name, Object value) throws ReflectiveOperationException {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }
}
