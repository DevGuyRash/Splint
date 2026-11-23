package splint.animals;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import splint.core.Log;
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
    /** Map cells are 256x256 squares; the animal population files are named after them. */
    public static final int CELL = 256;
    public static final int CHUNK = 8;

    private AnimalOps() {}

    /** Where an animal lives right now. */
    public enum Place { WORLD, HUTCH, TRAILER, INVENTORY }

    public record Found(IsoAnimal animal, Place place, Object holder, int x, int y, int z) {
        public int cellX() { return Math.floorDiv(x, CELL); }
        public int cellY() { return Math.floorDiv(y, CELL); }
    }

    // --- identification -------------------------------------------------------------------

    /**
     * An animal object whose construction never finished: no definition, no data. Vanilla
     * leaves these behind when a constructor returns early (water square, unknown type, or
     * the duplicate check) and every system that touches them throws.
     */
    public static boolean isGhost(IsoAnimal a) {
        return a.getAdef() == null || a.getData() == null || a.getAnimalType() == null
                || AnimalDefinitions.getDef(a.getAnimalType()) == null;
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
            b.append(" at ").append(x).append(',').append(y).append(',').append(z);
            b.append(" cell=").append(Math.floorDiv(x, CELL)).append(',').append(Math.floorDiv(y, CELL));
            b.append(" chunk=").append(Math.floorDiv(x, CHUNK)).append(',').append(Math.floorDiv(y, CHUNK));
            b.append(" apop=apop_").append(Math.floorDiv(x, CELL)).append('_').append(Math.floorDiv(y, CELL)).append(".bin");
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
            IsoPlayer near = nearestPlayer(a.getX(), a.getY());
            if (near != null) b.append(" nearestPlayer=").append(near.getUsername()).append(" dist=")
                    .append((int) Math.hypot(near.getX() - a.getX(), near.getY() - a.getY()));
        } catch (Throwable t) {
            b.append(" <describe failed: ").append(t).append('>');
        }
        return b.toString();
    }

    public static IsoPlayer nearestPlayer(float x, float y) {
        IsoPlayer best = null;
        double bestD = Double.MAX_VALUE;
        for (IsoPlayer p : players()) {
            double d = Math.hypot(p.getX() - x, p.getY() - y);
            if (d < bestD) { bestD = d; best = p; }
        }
        return best;
    }

    public static List<IsoPlayer> players() {
        List<IsoPlayer> out = new ArrayList<>();
        try {
            if (GameServer.server) {
                for (IsoPlayer p : GameServer.Players) if (p != null) out.add(p);
            } else {
                for (IsoPlayer p : IsoPlayer.players) if (p != null) out.add(p);
            }
        } catch (Throwable ignored) {
            // players list unavailable during startup
        }
        return out;
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
                for (int x = area.x0; x <= area.x1; x++) {
                    for (int y = area.y0; y <= area.y1; y++) {
                        for (int z = Math.max(area.z0, SCAN_Z_MIN); z <= Math.min(area.z1, SCAN_Z_MAX); z++) {
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
        for (IsoPlayer p : players()) {
            try {
                for (InventoryItem item : p.getInventory().getAllTypeRecurse("Animal")) {
                    if (item instanceof AnimalInventoryItem ai && ai.getAnimal() != null) {
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

    /** Every floor Build 42 can have. */
    static final int Z_MIN = -32, Z_MAX = 31;
    /**
     * Floors whose squares are scanned for square-only ghosts. Live animals on every floor are
     * found through the cell update list; scanning all 64 floors square by square would cost
     * too much for a command.
     */
    static final int SCAN_Z_MIN = -2, SCAN_Z_MAX = 3;

    /** A box of squares; z range inclusive. */
    public record Area(int x0, int y0, int x1, int y1, int z0, int z1) {
        public static Area cell(int cx, int cy) {
            return new Area(cx * CELL, cy * CELL, cx * CELL + CELL - 1, cy * CELL + CELL - 1, Z_MIN, Z_MAX);
        }
        public static Area radius(int x, int y, int r) { return new Area(x - r, y - r, x + r, y + r, Z_MIN, Z_MAX); }
        public boolean contains(int x, int y, int z) {
            return x >= x0 && x <= x1 && y >= y0 && y <= y1 && z >= z0 && z <= z1;
        }
    }

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
            if (cell.isSafeToAdd()) {
                cell.getObjectList().remove(a);
                cell.getRemoveList().remove(a);
            } else {
                cell.getRemoveList().add(a);
            }
            cell.getAddList().remove(a);
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
        String what = describe(a);
        quietly(() -> {
            BaseVehicle v = a.getVehicle();
            if (v != null) v.animals.remove(a);
        });
        quietly(() -> {
            ItemContainer c = a.getContainer();
            if (c == null) return;
            for (InventoryItem item : c.getAllType("Animal")) {
                if (item instanceof AnimalInventoryItem ai && ai.getAnimal() == a) removeCarried(ai);
            }
        });
        detach(a);
        Stats.ghostsRemoved.incrementAndGet();
        Log.problem("ghost-removed:" + where, "removed uninitialised animal (" + where + "): " + what, null);
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
            Stats.killedByCommand.incrementAndGet();
            return null;
        } catch (Throwable t) {
            Log.error("kill failed for " + describe(a), t);
            return t.toString();
        }
    }

    /** Takes a carried animal item out of its container and out of its carrier's hands. */
    static void removeCarried(AnimalInventoryItem item) {
        ItemContainer c = item.getContainer();
        for (IsoPlayer p : players()) {
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
