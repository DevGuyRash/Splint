package splint.animals;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import splint.core.Log;
import zombie.characters.animals.IsoAnimal;
import zombie.iso.IsoCell;
import zombie.iso.IsoChunk;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoMovingObject;
import zombie.iso.IsoWorld;
import zombie.iso.objects.IsoHutch;

/**
 * Replacements for the vanilla animal methods listed in {@code SplintAgent.PATCHES}. Each one
 * still runs the original code (through the {@code H} handle of its holder class) unless it has a reason not to,
 * and each reason is a failure reproduced in the lab (see docs/investigation.md).
 */
public final class AnimalHooks {
    // Each original is resolved in its own holder class, which the JVM initialises on the
    // first call of that hook only. A hook runs only if its own patch applied, so a patch that
    // failed to apply (e.g. after a game update) cannot break the others.
    private static final class Load { static final MethodHandle H = original(IsoAnimal.class, "load", void.class, ByteBuffer.class, int.class, boolean.class); }
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
    private static final class SaveReal { static final MethodHandle H = original("zombie.characters.animals.AnimalManagerMain",
            "saveRealAnimals", void.class); }


    private AnimalHooks() {}

    // --- identity ---------------------------------------------------------------------------

    /** Animals read from a save keep their ID; the counter moves past it. */
    public static void load(IsoAnimal self, ByteBuffer input, int worldVersion, boolean isDebugSave) throws Throwable {
        boolean outer = !AnimalIds.isLoading();
        AnimalIds.loading(true);
        try {
            Load.H.invokeExact(self, input, worldVersion, isDebugSave);
        } finally {
            if (outer) AnimalIds.loading(false);
        }
        AnimalIds.observe(self.getAnimalID());
    }

    // --- construction ---------------------------------------------------------------------

    /**
     * Vanilla deletes {@code this} when any animal within 4 squares has the same ID.
     *
     * <p>Called from every constructor before init, the ID is still -1, so the comparison can
     * only ever match a half-built animal (also -1); deleting the new, also half-built animal
     * then throws inside model cleanup ({@code AnimalDefinitions.getDef(null).textureSkinned})
     * and leaves it on its square as another ghost, so the failure repeats every frame and
     * spreads. Called from grow() after the new stage copied the parent's ID, a genuine
     * duplicate deletes both the parent and the grown animal: the animal vanishes.
     *
     * <p>Splint removes ghosts it finds in that window, never deletes a live animal over an
     * ID, and gives the newcomer a fresh unique ID instead.
     */
    public static boolean checkForChickenpocalypse(IsoAnimal self, IsoAnimal replacing) {
        IsoGridSquare origin = self.getSquare();
        if (origin == null) return false;
        IsoCell cell = origin.getCell();
        if (cell == null) return false;
        boolean constructing = self.getAdef() == null;
        int ox = origin.getX(), oy = origin.getY(), oz = origin.getZ();
        for (int x = ox - 4; x < ox + 4; x++) {
            for (int y = oy - 4; y < oy + 4; y++) {
                IsoGridSquare sq = cell.getGridSquare(x, y, oz);
                if (sq == null) continue;
                for (IsoAnimal other : sq.getAnimals()) {
                    if (other == self || other == replacing) continue;
                    if (AnimalOps.isGhost(other)) {
                        AnimalOps.removeGhost(other, "beside a new animal");
                        continue;
                    }
                    if (constructing || other.getAnimalID() != self.getAnimalID()) continue;
                    if (sameIndividual(other, self)) {
                        // A second object for the same animal (vanilla's "chickenpocalypse"
                        // case): remove the stray copy, keep the animal that is being updated.
                        String copy = AnimalOps.describe(other);
                        Incidents.record("duplicate copy removed", other);
                        AnimalOps.detach(other);
                        Stats.duplicateCopiesRemoved.incrementAndGet();
                        Log.problem("duplicate-copy", "removed a second object of the same animal (shared data/genome): "
                                + copy + " | kept: " + AnimalOps.describe(self), null);
                        continue;
                    }
                    IsoAnimal target = replacing != null ? replacing : self;
                    int old = target.getAnimalID();
                    int fresh = AnimalIds.allocate();
                    target.setAnimalID(fresh);
                    Stats.duplicateIdsFixed.incrementAndGet();
                    Incidents.record("duplicate id " + old + " re-assigned", target);
                    Log.problem("duplicate-id", "duplicate animal id " + old + " within 4 squares (vanilla deletes "
                            + (replacing != null ? "both the growing animal and its next stage" : "the new animal")
                            + "); kept both, new id " + fresh + " for " + AnimalOps.describe(target)
                            + " | other: " + AnimalOps.describe(other), null);
                }
            }
        }
        return false;
    }

    /** Two objects carrying the very same data or genome are copies of one animal. */
    private static boolean sameIndividual(IsoAnimal a, IsoAnimal b) {
        return (a.getData() != null && a.getData() == b.getData())
                || (a.fullGenome != null && a.fullGenome == b.fullGenome);
    }

    /**
     * Vanilla returns the constructor early when the square is water, leaving an animal with
     * no type, data or ID in the world. Its next update throws ({@code this.adef} is null)
     * and, because nothing catches that, the whole world update stops every frame. Splint
     * moves the animal to the nearest land within 8 squares instead.
     */
    public static boolean checkForWater(IsoAnimal self) {
        IsoGridSquare sq = self.getSquare();
        if (sq == null || !sq.isWaterSquare()) return false;
        boolean constructing = self.getAdef() == null;
        // Animals in a trailer, inventory or hutch keep a stale square; leave them to vanilla.
        if (!constructing && (self.getVehicle() != null || self.getContainer() != null || self.getHutch() != null)) {
            return true;
        }
        IsoGridSquare land = nearestLand(sq, 8);
        if (land != null) {
            String from = sq.getX() + "," + sq.getY() + "," + sq.getZ();
            self.setX(land.getX() + 0.5f);
            self.setY(land.getY() + 0.5f);
            self.setLastX(land.getX() + 0.5f);
            self.setLastY(land.getY() + 0.5f);
            self.setNextX(land.getX() + 0.5f);
            self.setNextY(land.getY() + 0.5f);
            self.setCurrent(land);
            self.setMovingSquareNow();
            Stats.waterRelocations.incrementAndGet();
            Incidents.record("moved off water", self);
            Log.problem("water-relocation", "animal " + (constructing ? "created" : "growing") + " on water at " + from
                    + " moved to land " + land.getX() + "," + land.getY() + "," + land.getZ()
                    + " (vanilla leaves an uninitialised animal that stops the world update)", null);
            return false;
        }
        if (constructing) {
            Stats.ghostsPrevented.incrementAndGet();
            Incidents.record("created on water, no land near", self);
            Log.problem("water-no-land", "animal created on water at " + sq.getX() + "," + sq.getY() + "," + sq.getZ()
                    + " with no land within 8 squares; initialising it there instead of leaving it half-built", null);
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

    // --- per-animal isolation ---------------------------------------------------------------

    public static void update(IsoAnimal self) {
        Log.gameStarted();
        Ticker.tick();
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
            Stats.hutchFailures.incrementAndGet();
            Incidents.record("hutch update failed: " + t.getClass().getSimpleName(), self.savedX, self.savedY, self.savedZ,
                    "hutch(" + self.getAnimalInside().size() + " inside)");
            Log.problem("hutch-update:" + t.getClass().getName() + ":" + t.getMessage(), "hutch at " + self.savedX + ","
                    + self.savedY + "," + self.savedZ + " cell=" + Math.floorDiv(self.savedX, AnimalOps.CELL) + ","
                    + Math.floorDiv(self.savedY, AnimalOps.CELL) + " (" + self.getAnimalInside().size()
                    + " animals inside) threw during update; contained", t);
        }
    }

    // --- removal ----------------------------------------------------------------------------

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
            Stats.removalFailures.incrementAndGet();
            Incidents.record("removal failed, completed", self);
            Log.problem("removeFromWorld:" + t.getClass().getName(), "removing animal from the world threw; finished the removal: "
                    + AnimalOps.describe(self), t);
            AnimalOps.detach(self);
        }
    }

    public static void delete(IsoAnimal self) {
        try {
            Delete.H.invokeExact(self);
        } catch (VirtualMachineError e) {
            throw e;
        } catch (Throwable t) {
            Stats.removalFailures.incrementAndGet();
            Incidents.record("delete failed, completed", self);
            Log.problem("delete:" + t.getClass().getName(), "deleting animal threw; finished the removal: "
                    + AnimalOps.describe(self), t);
            AnimalOps.detach(self);
        }
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
     * Chunk unload hands every animal on the chunk to the population manager. A ghost there
     * makes {@code unloaded()} throw ({@code getData().getAttachedTree()}), which abandons the
     * rest of the chunk's unload. Remove ghosts first.
     */
    public static void removeChunkFromWorld(Object manager, IsoChunk chunk) throws Throwable {
        try {
            for (int z = chunk.getMinLevel(); z <= chunk.getMaxLevel(); z++) {
                for (int y = 0; y < 8; y++) {
                    for (int x = 0; x < 8; x++) {
                        IsoGridSquare sq = chunk.getGridSquare(x, y, z);
                        if (sq == null || sq.getMovingObjects().isEmpty()) continue;
                        for (IsoMovingObject o : new ArrayList<>(sq.getMovingObjects())) {
                            if (o instanceof IsoAnimal a && AnimalOps.isGhost(a)) AnimalOps.removeGhost(a, "chunk unload");
                        }
                    }
                }
            }
        } catch (Throwable t) {
            Log.problem("chunk-unload-sweep", "ghost sweep before chunk unload failed", t);
        }
        ChunkUnload.H.invoke(manager, chunk);
    }

    public static void virtualizeAnimal(Object manager, IsoAnimal animal) throws Throwable {
        if (AnimalOps.isGhost(animal)) {
            AnimalOps.removeGhost(animal, "virtualize");
            return;
        }
        Virtualize.H.invoke(manager, animal);
    }

    /** The population save serialises every animal in the update list; a ghost there throws. */
    public static void saveRealAnimals(Object manager) throws Throwable {
        IsoCell cell = IsoWorld.instance == null ? null : IsoWorld.instance.getCell();
        if (cell != null) {
            for (IsoMovingObject o : new ArrayList<>(cell.getObjectList())) {
                if (o instanceof IsoAnimal a && AnimalOps.isGhost(a) && !a.isOnHook()) AnimalOps.removeGhost(a, "save");
            }
        }
        SaveReal.H.invoke(manager);
    }

    // --- plumbing ---------------------------------------------------------------------------

    private static MethodHandle original(Class<?> owner, String name, Class<?> ret, Class<?>... params) {
        try {
            return MethodHandles.publicLookup().findVirtual(owner, splint.agent.Patcher.ORIGINAL_PREFIX + name,
                    MethodType.methodType(ret, params));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Splint patch for " + owner.getName() + "." + name + " is not applied", e);
        }
    }

    private static MethodHandle original(String owner, String name, Class<?> ret, Class<?>... params) {
        try {
            Class<?> cls = Class.forName(owner);
            return MethodHandles.privateLookupIn(cls, MethodHandles.lookup()).findVirtual(cls, splint.agent.Patcher.ORIGINAL_PREFIX + name,
                    MethodType.methodType(ret, params));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Splint patch for " + owner + "." + name + " is not applied", e);
        }
    }
}
