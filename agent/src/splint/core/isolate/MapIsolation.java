package splint.core.isolate;

import java.util.concurrent.atomic.AtomicLong;
import splint.core.Counters;
import splint.core.log.Incidents;
import splint.core.log.Log;
import splint.core.world.World;
import zombie.iso.IsoChunk;
import zombie.iso.IsoMovingObject;
import zombie.iso.IsoWorld;
import zombie.network.ServerMap;

/**
 * The server's per-cell map work, routed here (see CoreModule), so one bad object costs only itself.
 *
 * <p>Vanilla's {@code ServerMap.postupdate} has one try around its loop over the loaded cells: a
 * cell that throws ends the loop, so every later cell is neither updated nor unloaded, and since
 * the failed cell stays in the list this repeats every tick (docs/investigation.md, "Its map area never unloads").
 *
 * <p>Failures are contained at the smallest unit first. An object that cannot be removed is
 * skipped and the rest of its chunk unloads. A chunk that cannot be removed is still saved, as
 * are the chunks after it. A cell that fails anyway is skipped for that tick, unless it still
 * holds chunks: then the failure goes on to vanilla's handler, which keeps the cell and retries,
 * because dropping it would lose chunks that were never saved.
 */
public final class MapIsolation {
    /** Server cells are 8x8 chunks of 8x8 squares. */
    private static final int CELL_SQUARES = 64;
    private static final AtomicLong CONTAINED = Counters.of("core", "mapFailuresContained");

    private MapIsolation() {}

    public static void updateCell(ServerMap.ServerCell cell) {
        try {
            cell.update();
        } catch (VirtualMachineError e) {
            throw e;
        } catch (Throwable t) {
            report("map cell update", cell.wx * CELL_SQUARES, cell.wy * CELL_SQUARES, 0, "map cell", t);
        }
    }

    public static void unloadCell(ServerMap.ServerCell cell) {
        try {
            cell.Unload();
        } catch (VirtualMachineError e) {
            throw e;
        } catch (Throwable t) {
            if (holdsChunks(cell)) throw t;
            report("map cell unload", cell.wx * CELL_SQUARES, cell.wy * CELL_SQUARES, 0, "map cell", t);
        }
    }

    public static void unloadChunk(IsoChunk chunk) {
        try {
            chunk.removeFromWorld();
        } catch (VirtualMachineError e) {
            throw e;
        } catch (Throwable t) {
            chunk.preventHotSave = false;            // as the method leaves it when it finishes
            report("chunk unload", chunk.wx * World.CHUNK, chunk.wy * World.CHUNK, 0, "chunk", t);
        }
    }

    public static void removeObject(IsoMovingObject o) {
        try {
            o.removeFromWorld();
        } catch (VirtualMachineError e) {
            throw e;
        } catch (Throwable t) {
            // Its chunk is going away, so it must not stay behind in the update list.
            if (IsoWorld.instance != null && IsoWorld.instance.currentCell != null) {
                IsoWorld.instance.currentCell.getObjectList().remove(o);
            }
            report(o.getClass().getSimpleName() + " removal", (int) Math.floor(o.getX()), (int) Math.floor(o.getY()),
                    (int) Math.floor(o.getZ()), Describers.brief(o), t);
        }
    }

    private static boolean holdsChunks(ServerMap.ServerCell cell) {
        for (IsoChunk[] column : cell.chunks) for (IsoChunk c : column) if (c != null) return true;
        return false;
    }

    private static void report(String what, int x, int y, int z, String subject, Throwable t) {
        CONTAINED.incrementAndGet();
        boolean full = Log.problem("ERROR", what + ":" + t.getClass().getName() + ":" + t.getMessage(),
                () -> what + " threw " + t + "; contained, the rest of the map continues; " + subject + " at "
                        + World.where(x, y, z) + World.whoIsNear(x, y), t);
        String kind = what + " threw " + t.getClass().getSimpleName();
        if (full) Incidents.record("core", kind, x, y, z, subject);
        else Incidents.count("core", kind, x, y);
    }
}
