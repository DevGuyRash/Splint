package splint.core.world;

import java.util.ArrayList;
import java.util.List;
import zombie.characters.IsoPlayer;
import zombie.network.GameServer;

/** Map geometry and who is where. Shared by every module's logging and commands. */
public final class World {
    /** Map cells are 256x256 squares (lotheaders and the apop_X_Y.bin population files use them). */
    public static final int CELL = 256;
    /** Chunks are 8x8 squares. */
    public static final int CHUNK = 8;

    private World() {}

    public static int cell(int coord) { return Math.floorDiv(coord, CELL); }

    public static int chunk(int coord) { return Math.floorDiv(coord, CHUNK); }

    /** "x,y,z cell=CX,CY chunk=WX,WY". */
    public static String where(int x, int y, int z) {
        return x + "," + y + "," + z + " cell=" + cell(x) + "," + cell(y) + " chunk=" + chunk(x) + "," + chunk(y);
    }

    /** Online players (server) or local players (client / single player). */
    public static List<IsoPlayer> players() {
        List<IsoPlayer> out = new ArrayList<>();
        try {
            if (GameServer.server) {
                for (IsoPlayer p : GameServer.Players) if (p != null) out.add(p);
            } else {
                for (IsoPlayer p : IsoPlayer.players) if (p != null) out.add(p);
            }
        } catch (Throwable ignored) {
            // player lists are not ready during start-up
        }
        return out;
    }

    public static IsoPlayer player(String name) {
        for (IsoPlayer p : players()) if (p.getUsername().equalsIgnoreCase(name)) return p;
        return null;
    }

    public static IsoPlayer nearestPlayer(float x, float y) {
        IsoPlayer best = null;
        double bestD = Double.MAX_VALUE;
        for (IsoPlayer p : players()) {
            double d = Math.hypot(p.getX() - x, p.getY() - y);
            if (d < bestD) {
                bestD = d;
                best = p;
            }
        }
        return best;
    }

    /** Usernames of players standing in map cell (cx, cy). */
    public static List<String> playersInCell(int cx, int cy) {
        List<String> out = new ArrayList<>();
        for (IsoPlayer p : players()) {
            if (cell((int) p.getX()) == cx && cell((int) p.getY()) == cy) out.add(p.getUsername());
        }
        return out;
    }

    /** " nearestPlayer=Bob dist=12 playersInCell=Bob,Ann" for log lines. */
    public static String whoIsNear(int x, int y) {
        StringBuilder b = new StringBuilder();
        IsoPlayer near = nearestPlayer(x, y);
        if (near != null) {
            b.append(" nearestPlayer=").append(near.getUsername()).append(" dist=")
                    .append((int) Math.hypot(near.getX() - x, near.getY() - y));
        }
        List<String> inCell = playersInCell(cell(x), cell(y));
        b.append(" playersInCell=").append(inCell.isEmpty() ? "none" : String.join(",", inCell));
        return b.toString();
    }
}
