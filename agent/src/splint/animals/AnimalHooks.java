package splint.animals;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import splint.core.log.Incidents;
import splint.core.log.Log;
import splint.core.patch.Patcher;
import splint.core.world.World;
import zombie.characters.animals.IsoAnimal;
import zombie.iso.IsoCell;
import zombie.iso.IsoChunk;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoMovingObject;
import zombie.iso.IsoWorld;
import zombie.iso.objects.IsoHutch;

/**
 * Replacements for the vanilla animal methods {@link AnimalsModule} lists. Each still runs the
 * original (through its holder's {@code H}) unless it has a reason not to, and each reason is
 * a failure reproduced in the lab (docs/investigation.md).
 *
 * <p>The per-tick hooks ({@link #update}, {@link #updateLoopingSounds}) cost three field reads
 * and one volatile read on the normal path; everything else runs on rare events.
 */
public final class AnimalHooks {
    // Each original resolves in its own holder, initialised on that hook's first call only, so a
    // patch that failed to apply (say after a game update) cannot break the others.
    private static final class Load { static final MethodHandle H = original(IsoAnimal.class, "load", void.class, ByteBuffer.class, int.class, boolean.class); }
    private static final class SetId { static final MethodHandle H = original(IsoAnimal.class, "setAnimalID", void.class, int.class); }
    private static final class CopyFrom { static final MethodHandle H = original(IsoAnimal.class, "copyFrom", void.class, IsoAnimal.class); }
    private static final class Update { static final MethodHandle H = original(IsoAnimal.class, "update", void.class); }
    private static final class Sounds { static final MethodHandle H = original(IsoAnimal.class, "updateLoopingSounds", void.class); }
    private static final class RemoveFromWorld { static final MethodHandle H = original(IsoAnimal.class, "removeFromWorld", void.class); }
    private static final class Delete { static final MethodHandle H = original(IsoAnimal.class, "delete", void.class); }
    private static final class Unloaded { static final MethodHandle H = original(IsoAnimal.class, "unloaded", void.class); }
    private static final class HutchUpdate { static final MethodHandle H = original(IsoHutch.class, "update", void.class); }
    private static final class ChunkUnload { static final MethodHandle H = original("zombie.characters.animals.AnimalPopulationManager",
            "removeChunkFromWorld", void.class, IsoChunk.class); }
    private static final class Virtualize { static final MethodHandle H = original("zombie.characters.animals.AnimalPopulationManager",
            "virtualizeAnimal", void.class, IsoAnimal.class); }
    private static final class Grow { static final MethodHandle H = original(zombie.characters.animals.datas.AnimalData.class,
            "grow", void.class, String.class); }
    private static final class SaveReal { static final MethodHandle H = original("zombie.characters.animals.AnimalManagerMain",
            "saveRealAnimals", void.class); }

    /** The animal whose grow() is running: its next stage may take over its ID. */
    private static IsoAnimal growing;

    private AnimalHooks() {}

    /** Growth replaces an animal by its next stage, which inherits the parent's ID. */
    public static void grow(zombie.characters.animals.datas.AnimalData self, String newType) throws Throwable {
        IsoAnimal outer = growing;
        growing = self.parent;
        try {
            Grow.H.invokeExact(self, newType);
        } finally {
            growing = outer;
        }
    }

    // --- identity ---------------------------------------------------------------------------

    /**
     * An animal restored from a save keeps its ID, and the counter moves past it. If another
     * live animal already carries that ID (two animals saved with the same vanilla random ID),
     * the newcomer gets a fresh one here, before anything can confuse the two.
     */
    public static void load(IsoAnimal self, ByteBuffer input, int worldVersion, boolean isDebugSave) throws Throwable {
        boolean outer = !AnimalIds.isLoading();
        AnimalIds.loading(true);
        try {
            Load.H.invokeExact(self, input, worldVersion, isDebugSave);
        } finally {
            if (outer) AnimalIds.loading(false);
        }
        int id = self.getAnimalID();
        AnimalIds.observe(id);
        claim(self, id, "loaded");
    }

    /**
     * setAnimalID moves an ID to this object. The game does that in one place, growth, where
     * the next stage takes over the growing parent's ID. Any other call
     * that would give this animal the ID of a different live animal (a mod, Lua) gets a fresh
     * ID instead: duplicates cannot be created, not just repaired.
     */
    public static void setAnimalID(IsoAnimal self, int id) throws Throwable {
        int old = self.getAnimalID();
        IsoAnimal holder = AnimalIndex.get(id);
        if (holder != null && holder != self && holder != growing && AnimalIndex.isLive(holder)) {
            int fresh = AnimalIds.allocate();
            AnimalStats.DUPLICATE_IDS_PREVENTED.incrementAndGet();
            AnimalOps.incident("setAnimalID(" + id + ") would duplicate; gave " + fresh, self);
            Log.problem("duplicate-id-set", "setAnimalID(" + id + ") would give " + AnimalOps.brief(self) + " the id of live "
                    + AnimalOps.describe(holder) + "; assigned " + fresh + " instead", null);
            id = fresh;
        }
        SetId.H.invokeExact(self, id);
        if (old != id) AnimalIndex.remove(old, self);
        AnimalIndex.put(id, self);
    }

    /** copyFrom moves an animal's identity to a new object (dropping, trailer unloading). */
    public static void copyFrom(IsoAnimal self, IsoAnimal source) throws Throwable {
        CopyFrom.H.invokeExact(self, source);
        AnimalIndex.put(self.getAnimalID(), self);
    }

    /** Indexes {@code self} under {@code id}, giving it a fresh ID if a different live animal has that one. */
    private static void claim(IsoAnimal self, int id, String how) {
        IsoAnimal other = AnimalIndex.get(id);
        if (other != null && other != self && AnimalIndex.isLive(other) && !sameIndividual(other, self)) {
            int fresh = AnimalIds.allocate();
            self.setAnimalID(fresh);                 // through the hook above: re-indexes self
            AnimalStats.DUPLICATE_IDS_FIXED.incrementAndGet();
            AnimalOps.incident("duplicate id " + id + " re-assigned (" + how + ")", self);
            Log.problem("duplicate-id", "animal " + how + " with id " + id + " that another live animal already has; gave it id "
                    + fresh + ": " + AnimalOps.describe(self) + " | other: " + AnimalOps.describe(other), null);
            return;
        }
        AnimalIndex.put(id, self);
    }

    /**
     * Vanilla deletes {@code this} when any animal within 4 squares shares its ID. Called from
     * every constructor before init, the ID is still -1, so it can only ever match another
     * half-built animal; deleting the new, also half-built animal then throws in model cleanup
     * and leaves it on its square as another ghost: the frame-by-frame failure in the report.
     * Called from grow() after the next stage copied the parent's ID, a duplicate deletes both
     * the parent and the next stage, silently.
     *
     * <p>Splint answers from the ID index in O(1): IDs are unique when issued and repaired when
     * loaded, so nothing is ever deleted over an ID here. A second object of the very same
     * animal (shared data or genome) is the one case vanilla's check was written for; it is
     * removed and the animal being updated is kept.
     */
    public static boolean checkForChickenpocalypse(IsoAnimal self, IsoAnimal replacing) {
        if (self.getAdef() == null) return false;                   // constructor: ID not assigned yet
        int id = self.getAnimalID();
        IsoAnimal other = AnimalIndex.get(id);
        if (other == null || other == self || other == replacing || !AnimalIndex.isLive(other)) return false;
        if (sameIndividual(other, self)) {
            AnimalOps.incident("duplicate copy removed", other);
            Log.problem("duplicate-copy", "removed a second object of the same animal (shared data/genome): "
                    + AnimalOps.describe(other) + " | kept: " + AnimalOps.describe(self), null);
            AnimalOps.detach(other);
            AnimalIndex.put(id, self);
            AnimalStats.DUPLICATE_COPIES_REMOVED.incrementAndGet();
        } else {
            claim(replacing != null ? replacing : self, id, replacing != null ? "growing" : "checked");
        }
        return false;
    }

    /** Two objects carrying the very same data or genome are copies of one animal. */
    private static boolean sameIndividual(IsoAnimal a, IsoAnimal b) {
        return (a.getData() != null && a.getData() == b.getData())
                || (a.fullGenome != null && a.fullGenome == b.fullGenome);
    }

    /**
     * Vanilla returns the constructor early when the square is water, leaving an animal with no
     * type, data or ID in the world; its next update throws and, as nothing catches that, the
     * world update stops every frame. Splint moves the animal to the nearest land within 8
     * squares (or, with none, initialises it where it is).
     */
    public static boolean checkForWater(IsoAnimal self) {
        IsoGridSquare sq = self.getSquare();
        if (sq == null || !sq.isWaterSquare()) return false;
        boolean constructing = self.getAdef() == null;
        // Animals in a trailer, inventory or hutch keep a stale square; leave them to vanilla.
        if (!constructing && (self.getVehicle() != null || self.getContainer() != null || self.getHutch() != null)) return true;
        IsoGridSquare land = nearestLand(sq, 8);
        if (land != null) {
            String from = sq.getX() + "," + sq.getY() + "," + sq.getZ();
            float x = land.getX() + 0.5f, y = land.getY() + 0.5f;
            self.setX(x);
            self.setY(y);
            self.setLastX(x);
            self.setLastY(y);
            self.setNextX(x);
            self.setNextY(y);
            self.setCurrent(land);
            self.setMovingSquareNow();
            AnimalStats.WATER_RELOCATIONS.incrementAndGet();
            AnimalOps.incident("moved off water", self);
            Log.problem("water-relocation", "animal " + (constructing ? "created" : "growing") + " on water at " + from
                    + " moved to land " + land.getX() + "," + land.getY() + "," + land.getZ()
                    + " (vanilla leaves an uninitialised animal that stops the world update)", null);
            return false;
        }
        if (constructing) {
            AnimalStats.GHOSTS_PREVENTED.incrementAndGet();
            AnimalOps.incident("created on water, no land near", self);
            return false;
        }
        return true;
    }

    private static IsoGridSquare nearestLand(IsoGridSquare origin, int radius) {
        IsoCell cell = origin.getCell();
        IsoGridSquare fallback = null;
        for (int r = 1; r <= radius; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dy = -r; dy <= r; dy++) {
                    if (Math.max(Math.abs(dx), Math.abs(dy)) != r) continue;
                    IsoGridSquare sq = cell.getGridSquare(origin.getX() + dx, origin.getY() + dy, origin.getZ());
                    if (sq == null || sq.isWaterSquare()) continue;
                    if (sq.isFree(false)) return sq;
                    if (fallback == null) fallback = sq;
                }
            }
            if (fallback != null) return fallback;
        }
        return null;
    }

    // --- per-animal isolation (hot path) ------------------------------------------------------

    public static void update(IsoAnimal self) {
        if (AnimalOps.isGhost(self) && !self.isOnHook()) {
            AnimalOps.removeGhost(self, "update");
            return;
        }
        if (Quarantine.isFrozen(self)) return;
        try {
            Update.H.invokeExact(self);
            Quarantine.succeeded(self);
        } catch (VirtualMachineError e) {
            throw e;
        } catch (Throwable t) {
            Quarantine.failed(self, "update", t);
        }
    }

    public static void updateLoopingSounds(IsoAnimal self) {
        if (AnimalOps.isGhost(self) || Quarantine.isFrozen(self)) return;
        try {
            Sounds.H.invokeExact(self);
        } catch (VirtualMachineError e) {
            throw e;
        } catch (Throwable t) {
            Quarantine.failed(self, "updateLoopingSounds", t);
        }
    }

    public static void hutchUpdate(IsoHutch self) {
        try {
            HutchUpdate.H.invokeExact(self);
        } catch (VirtualMachineError e) {
            throw e;
        } catch (Throwable t) {
            AnimalStats.HUTCH_FAILURES.incrementAndGet();
            Incidents.record(AnimalsModule.ID, "hutch update threw " + t.getClass().getSimpleName(),
                    self.savedX, self.savedY, self.savedZ, "hutch(" + self.getAnimalInside().size() + " inside)");
            Log.problem("hutch-update:" + t.getClass().getName(), "hutch at " + World.where(self.savedX, self.savedY, self.savedZ)
                    + " (" + self.getAnimalInside().size() + " animals inside) threw during update; contained", t);
        }
    }

    // --- removal --------------------------------------------------------------------------------

    public static void removeFromWorld(IsoAnimal self) {
        if (AnimalOps.isGhost(self)) {
            AnimalOps.detach(self);
            return;
        }
        try {
            RemoveFromWorld.H.invokeExact(self);
        } catch (VirtualMachineError e) {
            throw e;
        } catch (Throwable t) {
            AnimalStats.REMOVAL_FAILURES.incrementAndGet();
            AnimalOps.incident("removal threw, completed", self);
            Log.problem("removeFromWorld:" + t.getClass().getName(), "removing animal from the world threw; finished the removal: "
                    + AnimalOps.describe(self), t);
            AnimalOps.detach(self);
        }
    }

    /** delete() is the end of an animal object; its ID leaves the index (unless taken over). */
    public static void delete(IsoAnimal self) {
        try {
            Delete.H.invokeExact(self);
        } catch (VirtualMachineError e) {
            throw e;
        } catch (Throwable t) {
            AnimalStats.REMOVAL_FAILURES.incrementAndGet();
            AnimalOps.incident("delete threw, completed", self);
            Log.problem("delete:" + t.getClass().getName(), "deleting animal threw; finished the removal: "
                    + AnimalOps.describe(self), t);
            AnimalOps.detach(self);
        }
        AnimalIndex.remove(self.getAnimalID(), self);
    }

    public static void unloaded(IsoAnimal self) {
        if (AnimalOps.isGhost(self)) return;
        try {
            Unloaded.H.invokeExact(self);
        } catch (VirtualMachineError e) {
            throw e;
        } catch (Throwable t) {
            Log.problem("unloaded:" + t.getClass().getName(), "animal unload bookkeeping threw; contained: "
                    + AnimalOps.describe(self), t);
        }
    }

    /**
     * Chunk unload hands every animal on the chunk to the population manager, which turns them
     * into virtual animals. A ghost there makes unloaded() throw (getData().getAttachedTree()),
     * which abandons the rest of the chunk's unload, so ghosts go first. The unloaded animals'
     * objects are finished with, so their IDs leave the index.
     */
    public static void removeChunkFromWorld(Object manager, IsoChunk chunk) throws Throwable {
        List<IsoAnimal> animals = null;
        try {
            for (int z = chunk.getMinLevel(); z <= chunk.getMaxLevel(); z++) {
                for (int y = 0; y < 8; y++) {
                    for (int x = 0; x < 8; x++) {
                        IsoGridSquare sq = chunk.getGridSquare(x, y, z);
                        if (sq == null) continue;
                        List<IsoMovingObject> mov = sq.getMovingObjects();
                        for (int i = 0; i < mov.size(); i++) {
                            if (mov.get(i) instanceof IsoAnimal a) {
                                if (animals == null) animals = new ArrayList<>();
                                animals.add(a);
                            }
                        }
                    }
                }
            }
            if (animals != null) {
                for (IsoAnimal a : animals) {
                    if (AnimalOps.isGhost(a)) AnimalOps.removeGhost(a, "chunk unload");
                    // Vanilla's population worker drops an animal whose ID repeats within a
                    // virtual group, so a duplicate must be fixed before it is virtualised.
                    else claim(a, a.getAnimalID(), "unloading");
                }
            }
        } catch (Throwable t) {
            Log.problem("chunk-unload-sweep", "ghost sweep before chunk unload failed", t);
        }
        ChunkUnload.H.invoke(manager, chunk);
        if (animals != null) for (IsoAnimal a : animals) AnimalIndex.remove(a.getAnimalID(), a);
    }

    public static void virtualizeAnimal(Object manager, IsoAnimal animal) throws Throwable {
        if (AnimalOps.isGhost(animal)) {
            AnimalOps.removeGhost(animal, "virtualize");
            return;
        }
        Virtualize.H.invoke(manager, animal);
        AnimalIndex.remove(animal.getAnimalID(), animal);
    }

    /** The population save serialises every animal in the update list; a ghost there throws. */
    public static void saveRealAnimals(Object manager) throws Throwable {
        IsoCell cell = IsoWorld.instance == null ? null : IsoWorld.instance.getCell();
        if (cell != null) {
            List<IsoAnimal> ghosts = null;
            for (IsoMovingObject o : cell.getObjectList()) {
                if (o instanceof IsoAnimal a && AnimalOps.isGhost(a) && !a.isOnHook()) {
                    if (ghosts == null) ghosts = new ArrayList<>();
                    ghosts.add(a);
                }
            }
            if (ghosts != null) for (IsoAnimal a : ghosts) AnimalOps.removeGhost(a, "save");
        }
        SaveReal.H.invoke(manager);
    }

    // --- plumbing ---------------------------------------------------------------------------

    private static MethodHandle original(Class<?> owner, String name, Class<?> ret, Class<?>... params) {
        try {
            return MethodHandles.publicLookup().findVirtual(owner, Patcher.ORIGINAL_PREFIX + name, MethodType.methodType(ret, params));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Splint patch for " + owner.getName() + "." + name + " is not applied", e);
        }
    }

    private static MethodHandle original(String owner, String name, Class<?> ret, Class<?>... params) {
        try {
            Class<?> cls = Class.forName(owner);
            return MethodHandles.privateLookupIn(cls, MethodHandles.lookup())
                    .findVirtual(cls, Patcher.ORIGINAL_PREFIX + name, MethodType.methodType(ret, params));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Splint patch for " + owner + "." + name + " is not applied", e);
        }
    }
}
