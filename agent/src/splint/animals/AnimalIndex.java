package splint.animals;

import splint.core.util.IntWeakMap;
import splint.core.world.World;
import zombie.characters.IsoPlayer;
import zombie.characters.animals.IsoAnimal;
import zombie.inventory.ItemContainer;
import zombie.iso.IsoCell;
import zombie.iso.objects.IsoHutch;
import zombie.vehicles.BaseVehicle;

/**
 * Animal ID → the object currently carrying it, for every animal in server memory. It replaces
 * vanilla's duplicate check (scan 8×8 squares, allocating a list per square, on every animal
 * construction, and blind beyond 4 squares) with one O(1) lookup that sees the whole server.
 * Values are weak, so objects the game drops silently (a hutch or trailer unloading with its
 * chunk, a player logging out with an animal in a bag) neither leak nor keep their ID taken.
 *
 * <p>Main game thread only. Work from other threads (the chunk loader) reaches it through
 * {@code Threads.onMain}. Entries are never removed when an animal object goes away: the weak
 * value and {@link #isLive} make a stale entry harmless, while removing entries too eagerly
 * (an animal that turns virtual and comes back as the same object) would hide real
 * duplicates. A missing entry only means one fewer duplicate caught, never a wrong re-ID.
 */
final class AnimalIndex {
    private static final IntWeakMap<IsoAnimal> BY_ID = new IntWeakMap<>(8192);

    private AnimalIndex() {}

    static IsoAnimal get(int id) { return id < 0 ? null : BY_ID.get(id); }

    static void put(int id, IsoAnimal a) { if (id >= 0) BY_ID.put(id, a); }

    static void remove(int id, IsoAnimal a) { if (id >= 0) BY_ID.remove(id, a); }

    static int size() { return BY_ID.size(); }

    /**
     * Whether an indexed object is still a real animal somewhere: in the cell's update list, in
     * a hutch that exists, in a trailer that exists, carried by an online player, or on a hook.
     * Only called on an ID collision, so its cost never lands on a normal path.
     */
    static boolean isLive(IsoAnimal a) {
        try {
            if (a.isDead()) return false;
            IsoCell cell = a.getCell();
            if (cell != null && (cell.getObjectList().contains(a) || cell.getAddList().contains(a))) return true;
            if (a.isOnHook()) return true;
            IsoHutch h = a.getHutch();
            // A hutch counts only while the hutch manager still updates it: an unloaded hutch's
            // object can linger, still claiming its animals, until its square is recycled.
            if (h != null && h.isExistInTheWorld() && AnimalOps.hutches().contains(h)
                    && (h.getAnimalInside().containsValue(a) || a.nestBox >= 0)) return true;
            BaseVehicle v = a.getVehicle();
            if (v != null && v.animals.contains(a) && cell != null && cell.getVehicles().contains(v)) return true;
            ItemContainer c = a.getContainer();
            if (c != null && c.getParent() instanceof IsoPlayer p) return World.players().contains(p);
        } catch (Throwable ignored) {
            // a half-torn-down object is not live
        }
        return false;
    }
}
