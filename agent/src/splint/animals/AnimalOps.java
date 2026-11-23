package splint.animals;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import splint.core.log.Incidents;
import splint.core.log.Log;
import splint.core.world.Area;
import splint.core.world.World;
import zombie.MovingObjectUpdateScheduler;
import zombie.characters.IsoPlayer;
import zombie.characters.animals.AnimalDefinitions;
import zombie.characters.animals.IsoAnimal;
import zombie.characters.animals.datas.AnimalData;
import zombie.inventory.InventoryItem;
import zombie.inventory.ItemContainer;
import zombie.inventory.types.AnimalInventoryItem;
import zombie.iso.IsoCell;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoMovingObject;
import zombie.iso.IsoWorld;
import zombie.iso.objects.IsoHutch;
import zombie.network.GameServer;
import zombie.popman.animal.HutchManager;
import zombie.vehicles.BaseVehicle;

/** Finding, describing and removing animals, wherever they are stored. */
public final class AnimalOps {
    private AnimalOps() {}

    /** Where an animal lives right now. */
    public enum Place { WORLD, HUTCH, TRAILER, INVENTORY }

    public record Found(IsoAnimal animal, Place place, Object holder, int x, int y, int z) {
        public int cellX() { return World.cell(x); }
        public int cellY() { return World.cell(y); }
    }

    // --- identification -------------------------------------------------------------------

    /**
     * An animal object whose construction never finished: no definition, no data. Vanilla
     * leaves these behind when a constructor returns early (water square, unknown type, or
     * the duplicate check) and every system that touches them throws.
     */
    public static boolean isGhost(IsoAnimal a) {
        // Called for every animal on every update: field reads only (init sets adef from the
        // type's definition, so a non-null adef already implies a known type).
        return a.getAdef() == null || a.getData() == null || a.getAnimalType() == null;
    }

    public static String describe(IsoAnimal a) {
        StringBuilder b = new StringBuilder(160);
        try {
            String type = a.getAnimalType();
            b.append("animal type=").append(type == null ? "<none>" : type);
            AnimalData data = a.getData();
            if (data != null && data.getBreed() != null) b.append(" breed=").append(data.getBreed().getName());
            b.append(" id=").append(a.getAnimalID()).append(" onlineId=").append(a.getOnlineID());
            if (isGhost(a)) b.append(" GHOST(uninitialised)");
            int x = (int) Math.floor(a.getX()), y = (int) Math.floor(a.getY()), z = (int) Math.floor(a.getZ());
            b.append(" at ").append(World.where(x, y, z));
            b.append(" apop=apop_").append(World.cell(x)).append('_').append(World.cell(y)).append(".bin");
            IsoGridSquare sq = a.getSquare();
            if (sq != null && sq.isWaterSquare()) b.append(" onWater");
            if (data != null) {
                b.append(" ageDays=").append(data.getAge()).append(" daysSurvived=").append(data.getDaysSurvived());
                if (data.getAttachedPlayer() != null) b.append(" leashedTo=").append(data.getAttachedPlayer().getUsername());
            }
            if (a.getMother() != null) b.append(" motherId=").append(a.getMother().getAnimalID());
            IsoHutch hutch = a.getHutch();
            if (hutch != null) b.append(" hutch=").append(hutch.savedX).append(',').append(hutch.savedY).append(',').append(hutch.savedZ);
            BaseVehicle v = a.getVehicle();
            if (v != null) b.append(" trailer=").append(v.getScriptName()).append("#").append(v.getId())
                    .append('@').append((int) v.getX()).append(',').append((int) v.getY());
            if (a.isOnHook()) b.append(" onHook");
            b.append(World.whoIsNear(x, y));
        } catch (Throwable t) {
            b.append(" <describe failed: ").append(t).append('>');
        }
        return b.toString();
    }

    /** Records an incident about {@code a} for /splint problems. */
    static void incident(String kind, IsoAnimal a) {
        incident(kind, a, true);
    }

    /**
     * Records an incident about {@code a} for /splint problems. With {@code detailed} false only
     * the totals are counted (no player lookups): for repeats the log already rate-limits.
     */
    static void incident(String kind, IsoAnimal a, boolean detailed) {
        int x = (int) Math.floor(a.getX()), y = (int) Math.floor(a.getY());
        if (detailed) Incidents.record(AnimalsModule.ID, kind, x, y, (int) Math.floor(a.getZ()), brief(a));
        else Incidents.count(AnimalsModule.ID, kind, x, y);
    }

    /** "chick#10421" (or "GHOST" for an uninitialised animal): short enough for lists. */
    public static String brief(IsoAnimal a) {
        return (isGhost(a) ? "GHOST" : a.getAnimalType()) + "#" + a.getAnimalID();
    }


    /**
     * Carried animals in a container and every bag inside it. Found by the game's own item class,
     * not an item type name, so animal items added by mods or later game versions count too.
     */
    public static List<AnimalInventoryItem> carried(ItemContainer c) {
        List<AnimalInventoryItem> out = new ArrayList<>();
        if (c == null) return out;
        for (InventoryItem item : c.getAllRecurse(i -> i instanceof AnimalInventoryItem)) {
            out.add((AnimalInventoryItem) item);
        }
        return out;
    }

    /** Whether the animal is a juvenile that will still grow (vanilla isBaby, safe on ghosts). */
    public static boolean isJuvenile(IsoAnimal a) {
        try {
            return a.getData() != null && a.getData().currentStage != null && a.isBaby();
        } catch (Throwable t) {
            return false;
        }
    }



    // --- enumeration ----------------------------------------------------------------------

    /**
     * Every animal the server currently has in memory: on the map (including ghosts that are
     * only referenced by a square), inside hutches, in trailers and in player inventories.
     * Squares are scanned only inside {@code area} (null = the cell update list only, which
     * covers every live map animal but not square-only ghosts).
     */
    public static List<Found> all(Area area) {
        Map<IsoAnimal, Found> seen = new IdentityHashMap<>();
        IsoCell cell = IsoWorld.instance == null ? null : IsoWorld.instance.getCell();
        if (cell != null) {
            for (IsoMovingObject o : new ArrayList<>(cell.getObjectList())) {
                if (o instanceof IsoAnimal a && a.getHutch() == null && a.getVehicle() == null && !a.isOnHook()) {
                    add(seen, a, Place.WORLD, null);
                }
            }
            if (area != null) {
                for (int x = area.x0(); x <= area.x1(); x++) {
                    for (int y = area.y0(); y <= area.y1(); y++) {
                        for (int z = Math.max(area.z0(), SCAN_Z_MIN); z <= Math.min(area.z1(), SCAN_Z_MAX); z++) {
                            IsoGridSquare sq = cell.getGridSquare(x, y, z);
                            if (sq == null) continue;
                            for (IsoMovingObject o : sq.getMovingObjects()) {
                                if (o instanceof IsoAnimal a && !a.isOnHook()) add(seen, a, Place.WORLD, null);
                            }
                        }
                    }
                }
            }
            try {
                for (BaseVehicle v : new ArrayList<>(cell.getVehicles())) {
                    for (IsoAnimal a : new ArrayList<>(v.getAnimals())) {
                        seen.put(a, new Found(a, Place.TRAILER, v, (int) v.getX(), (int) v.getY(), (int) v.getZ()));
                    }
                }
            } catch (Throwable t) {
                Log.problem("enumerate-trailers", "could not list trailer animals", t);
            }
        }
        try {
            for (IsoHutch h : hutches()) {
                for (IsoAnimal a : new ArrayList<>(h.getAnimalInside().values())) {
                    if (a != null) seen.put(a, new Found(a, Place.HUTCH, h, h.savedX, h.savedY, h.savedZ));
                }
            }
        } catch (Throwable t) {
            Log.problem("enumerate-hutches", "could not list hutch animals", t);
        }
        for (IsoPlayer p : World.players()) {
            try {
                for (AnimalInventoryItem ai : carried(p.getInventory())) {
                    if (ai.getAnimal() != null) {
                        seen.put(ai.getAnimal(), new Found(ai.getAnimal(), Place.INVENTORY, ai,
                                (int) p.getX(), (int) p.getY(), (int) p.getZ()));
                    }
                }
            } catch (Throwable t) {
                Log.problem("enumerate-inventory", "could not list animals carried by " + p.getUsername(), t);
            }
        }
        List<Found> out = new ArrayList<>(seen.values());
        if (area != null) out.removeIf(f -> !area.contains(f.x, f.y, f.z));
        return out;
    }

    private static void add(Map<IsoAnimal, Found> seen, IsoAnimal a, Place place, Object holder) {
        seen.putIfAbsent(a, new Found(a, place, holder, (int) Math.floor(a.getX()), (int) Math.floor(a.getY()),
                (int) Math.floor(a.getZ())));
    }

    @SuppressWarnings("unchecked")
    public static List<IsoHutch> hutches() {
        try {
            var f = HutchManager.class.getDeclaredField("hutchList");
            f.setAccessible(true);
            return new ArrayList<>((List<IsoHutch>) f.get(HutchManager.getInstance()));
        } catch (ReflectiveOperationException e) {
            Log.problem("hutch-list", "cannot read HutchManager.hutchList", e);
            return List.of();
        }
    }

    /**
     * Floors whose squares are scanned for square-only ghosts. Live animals on every floor are
     * found through the cell update list; scanning all 64 floors square by square would cost
     * too much for a command.
     */
    static final int SCAN_Z_MIN = -2, SCAN_Z_MAX = 3;

    // --- removal --------------------------------------------------------------------------

    private static final MethodHandle MANAGER_REMOVE;
    private static final MethodHandle MANAGER_INSTANCE;
    static {
        MethodHandle remove = null, instance = null;
        try {
            Class<?> manager = Class.forName("zombie.characters.animals.AnimalManagerMain");
            MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(manager, MethodHandles.lookup());
            remove = lookup.findVirtual(manager, "removeFromWorld", MethodType.methodType(void.class, IsoAnimal.class));
            instance = lookup.findStatic(manager, "getInstance", MethodType.methodType(manager));
        } catch (ReflectiveOperationException e) {
            Log.error("AnimalManagerMain.removeFromWorld unavailable; ghost removal will skip it", e);
        }
        MANAGER_REMOVE = remove;
        MANAGER_INSTANCE = instance;
    }

    /**
     * Takes an animal out of every world structure without touching its model or definition,
     * so it works on ghosts. This is what vanilla delete() would do if releasing the ragdoll
     * of an undefined animal did not throw first.
     */
    public static void detach(IsoAnimal a) {
        IsoCell cell = a.getCell();
        if (cell != null) {
            // Directly, as vanilla IsoAnimal.removeFromWorld does: the cell's deferred remove
            // list skips every living IsoPlayer, and animals are IsoPlayers, so queueing the
            // animal there would leave it in the update list forever.
            cell.getObjectList().remove(a);
            cell.getAddList().remove(a);
            cell.getRemoveList().remove(a);
        }
        quietly(() -> MovingObjectUpdateScheduler.instance.removeObject(a));
        quietly(() -> { if (a.getHutch() != null) a.getHutch().animalOutside.remove(a); });
        quietly(() -> { if (MANAGER_REMOVE != null) MANAGER_REMOVE.invoke(MANAGER_INSTANCE.invoke(), a); });
        quietly(a::removeFromSquare);
        quietly(() -> a.setSquare(null));
        quietly(a::removeFromUpdateLists);
    }

    /**
     * Removes a ghost and records it. Ghosts can also sit in a trailer's animal list or in an
     * inventory item, which update them directly; take them out of those holders too, or the
     * holder would keep updating (and saving) them.
     */
    public static void removeGhost(IsoAnimal a, String where) {
        // Described before it is detached, but only if the log will print it.
        boolean full = Log.problem("ghost-removed:" + where, () -> "removed uninitialised animal (" + where + "): " + describe(a), null);
        incident("ghost removed (" + where + ")", a, full);
        quietly(() -> {
            BaseVehicle v = a.getVehicle();
            if (v != null) v.animals.remove(a);
        });
        quietly(() -> {
            ItemContainer c = a.getContainer();
            if (c == null) return;
            for (AnimalInventoryItem ai : carried(c)) {
                if (ai.getAnimal() == a) removeCarried(ai);
            }
        });
        detach(a);
        AnimalStats.GHOSTS_REMOVED.incrementAndGet();
    }

    /** Kills (removes) one animal wherever it is. Returns a short reason when it could not. */
    public static String kill(Found f) {
        IsoAnimal a = f.animal();
        try {
            switch (f.place()) {
                case HUTCH -> {
                    ((IsoHutch) f.holder()).removeAnimal(a);
                    quietly(a::removeFromUpdateLists);
                }
                case TRAILER -> {
                    BaseVehicle v = (BaseVehicle) f.holder();
                    if (v.animals.remove(a)) {
                        v.setCurrentTotalAnimalSize(Math.max(0f, v.getCurrentTotalAnimalSize() - a.getAnimalTrailerSize()));
                    }
                    quietly(a::removeFromUpdateLists);
                }
                case INVENTORY -> {
                    removeCarried((AnimalInventoryItem) f.holder());
                    quietly(a::removeFromUpdateLists);
                }
                case WORLD -> {
                    if (isGhost(a)) detach(a);
                    else {
                        try {
                            a.remove();
                        } catch (Throwable t) {
                            detach(a);
                        }
                    }
                }
            }
            AnimalStats.KILLED_BY_COMMAND.incrementAndGet();
            return null;
        } catch (Throwable t) {
            Log.error("kill failed for " + describe(a), t);
            return t.toString();
        }
    }

    /** Takes a carried animal item out of its container and out of its carrier's hands. */
    static void removeCarried(AnimalInventoryItem item) {
        ItemContainer c = item.getContainer();
        for (IsoPlayer p : World.players()) {
            if (p.getPrimaryHandItem() == item) p.setPrimaryHandItem(null);
            if (p.getSecondaryHandItem() == item) p.setSecondaryHandItem(null);
        }
        if (c != null) {
            c.Remove(item);
            if (GameServer.server) GameServer.sendRemoveItemFromContainer(c, item);
        }
    }

    public static Predicate<Found> ofType(String type) {
        if (type == null || type.isEmpty() || type.equalsIgnoreCase("all")) return f -> true;
        String t = type.toLowerCase();
        return f -> {
            String at = f.animal().getAnimalType();
            if (at == null) return t.equals("ghost") || t.equals("ghosts");
            String group = f.animal().getAdef() == null ? null : f.animal().getAdef().getGroup();
            return at.equalsIgnoreCase(t) || (group != null && group.equalsIgnoreCase(t));
        };
    }

    public interface Action { void run() throws Throwable; }

    public static void quietly(Action action) {
        try {
            action.run();
        } catch (Throwable ignored) {
            // best-effort cleanup step; the next steps still run
        }
    }
}
