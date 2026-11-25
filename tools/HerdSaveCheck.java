import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;

/**
 * Behaviour check for the animal save guards, run by build.py --verify in the game's own JRE with
 * the agent attached: real herds (VirtualAnimal) holding animals vanilla cannot save are saved with
 * the real save method. In vanilla each makes IsoAnimal.save throw, which aborts the whole save.
 * What must be on disk is what the same save method writes for a herd without that animal.
 *
 * <ul>
 *   <li>a half-built animal (no type, no data, as vanilla leaves one on a water square) is dropped;
 *   <li>a complete animal that cannot be written is left out of the file but stays in the herd;
 *   <li>an animal without a breed is given one from its definition;
 *   <li>a save that fails because its buffer is full still fails, with the herd untouched.
 * </ul>
 * Exits non-zero when any of these does not hold.
 */
public final class HerdSaveCheck {
    private static final String ANIMALS = "zombie.characters.animals.";
    private static Object unsafe;
    private static Method allocate;

    public static void main(String[] args) throws Exception {
        // Bare instances, so no running world (or its random-number setup) is needed. A bare
        // IsoAnimal has every field null, which is exactly what a half-built animal looks like to
        // the save: no adef, no data, no type.
        Field unsafeField = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        unsafe = unsafeField.get(null);
        allocate = unsafe.getClass().getMethod("allocateInstance", Class.class);

        byte[] emptyHerd = bytes(herd(new ArrayList<>()), 1 << 16);

        ArrayList<Object> withGhost = new ArrayList<>();
        withGhost.add(bare("IsoAnimal"));
        check(Arrays.equals(emptyHerd, bytes(herd(withGhost), 1 << 16)), "a herd with a half-built animal did not save as a herd without it");
        check(withGhost.isEmpty(), "the half-built animal is still in the herd");

        // Type, definition and data, so not half-built; everything else the save needs is missing.
        ArrayList<Object> withUnsaveable = new ArrayList<>();
        Object unsaveable = built();
        withUnsaveable.add(unsaveable);
        check(Arrays.equals(emptyHerd, bytes(herd(withUnsaveable), 1 << 16)), "a herd with an unsaveable animal did not save as a herd without it");
        check(withUnsaveable.size() == 1 && withUnsaveable.get(0) == unsaveable, "the unsaveable animal was removed from the herd");

        Object data = get(unsaveable, "data");
        Object breeds = get(get(unsaveable, "adef"), "breeds");
        check(get(data, "breed") == ((ArrayList<?>) breeds).get(0), "an animal without a breed was not given its type's first breed");

        ArrayList<Object> tooBig = new ArrayList<>();
        tooBig.add(built());
        try {
            bytes(herd(tooBig), emptyHerd.length - 1);
            check(false, "a full buffer did not fail the save");
        } catch (InvocationTargetException e) {
            check(e.getCause() instanceof BufferOverflowException, "a full buffer failed the save with " + e.getCause());
            check(tooBig.size() == 1, "a full buffer removed an animal from the herd");
        }
        System.out.println("herd save check: herds holding a half-built animal, an unsaveable animal and one without a breed "
                + "saved cleanly (" + emptyHerd.length + " bytes each, 0 animals)");
    }

    /** An animal that is not half-built but has nothing else: no stats, no genome, no breed. */
    private static Object built() throws ReflectiveOperationException {
        Object breed = allocate.invoke(unsafe, Class.forName(ANIMALS + "datas.AnimalBreed"));
        set(breed, "name", "check");
        ArrayList<Object> breeds = new ArrayList<>();
        breeds.add(breed);
        Object def = bare("AnimalDefinitions");
        set(def, "breeds", breeds);
        Object animal = bare("IsoAnimal");
        set(animal, "type", "check");
        set(animal, "adef", def);
        set(animal, "data", allocate.invoke(unsafe, Class.forName(ANIMALS + "datas.AnimalData")));
        return animal;
    }

    private static Object herd(ArrayList<Object> animals) throws ReflectiveOperationException {
        Object herd = bare("VirtualAnimal");
        set(herd, "animals", animals);
        set(herd, "forwardDirection", Class.forName("org.joml.Vector2f").getDeclaredConstructor().newInstance());
        set(herd, "migrationGroup", "");
        return herd;
    }

    private static byte[] bytes(Object herd, int capacity) throws ReflectiveOperationException {
        Method save = herd.getClass().getDeclaredMethod("save", ByteBuffer.class);
        save.setAccessible(true);
        ByteBuffer out = ByteBuffer.allocate(capacity);
        save.invoke(herd, out);
        return Arrays.copyOf(out.array(), out.position());
    }

    private static Object bare(String name) throws ReflectiveOperationException {
        return allocate.invoke(unsafe, Class.forName(ANIMALS + name));
    }

    private static void check(boolean ok, String problem) {
        if (ok) return;
        System.out.println("herd save check: FAILED, " + problem);
        System.exit(1);
    }

    private static Field field(Object target, String name) throws ReflectiveOperationException {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    private static Object get(Object target, String name) throws ReflectiveOperationException {
        return field(target, name).get(target);
    }

    private static void set(Object target, String name, Object value) throws ReflectiveOperationException {
        field(target, name).set(target, value);
    }
}
