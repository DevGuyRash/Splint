package splint.core.log;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import splint.core.world.World;
import zombie.characters.IsoPlayer;

/**
 * Every problem any module handled since start, with where it happened and who was there.
 * On a server with 100+ players nobody knows in advance which cell or player is involved;
 * {@code /splint problems} reads this to answer "where is it happening, and who is there".
 * Recording costs a little (it looks up players), so call it when something went wrong,
 * never on a normal path.
 */
public final class Incidents {
    private static final int KEEP = 500;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    public record Incident(String time, String module, String kind, int x, int y, int z, String subject,
                           String nearestPlayer, List<String> playersInCell) {
        public int cellX() { return World.cell(x); }

        public int cellY() { return World.cell(y); }

        public String line() {
            return time + " [" + module + "] " + kind + " at " + x + "," + y + "," + z + " cell " + cellX() + "," + cellY()
                    + " " + subject + (nearestPlayer.isEmpty() ? "" : " nearest " + nearestPlayer)
                    + (playersInCell.isEmpty() ? " (no player in cell)" : " in cell: " + String.join(",", playersInCell));
        }
    }

    private static final Deque<Incident> RECENT = new ArrayDeque<>();
    private static final Map<String, Long> BY_KIND = new TreeMap<>();
    private static final Map<String, Long> BY_CELL = new HashMap<>();

    private Incidents() {}

    /**
     * Counts an occurrence without the details (no player lookups): for repeats that the log
     * already rate-limits. Kind and cell totals stay exact.
     */
    public static void count(String module, String kind, int x, int y) {
        synchronized (Incidents.class) {
            BY_KIND.merge(module + ": " + Log.stableKey(kind), 1L, Long::sum);
            BY_CELL.merge(World.cell(x) + "," + World.cell(y), 1L, Long::sum);
        }
    }

    public static void record(String module, String kind, int x, int y, int z, String subject) {
        try {
            IsoPlayer near = World.nearestPlayer(x, y);
            String nearest = near == null ? "" : near.getUsername() + "@" + (int) Math.hypot(near.getX() - x, near.getY() - y);
            Incident i = new Incident(LocalTime.now().format(TIME), module, kind, x, y, z, subject, nearest,
                    World.playersInCell(World.cell(x), World.cell(y)));
            synchronized (Incidents.class) {
                RECENT.addFirst(i);
                while (RECENT.size() > KEEP) RECENT.removeLast();
                BY_KIND.merge(module + ": " + Log.stableKey(kind), 1L, Long::sum);
                BY_CELL.merge(i.cellX() + "," + i.cellY(), 1L, Long::sum);
            }
        } catch (Throwable ignored) {
            // recording must never add a failure of its own
        }
    }

    public static synchronized List<Incident> recent(int n) {
        List<Incident> out = new ArrayList<>(Math.min(n, RECENT.size()));
        for (Incident i : RECENT) {
            if (out.size() == n) break;
            out.add(i);
        }
        return out;
    }

    public static synchronized Map<String, Long> byKind() {
        return new TreeMap<>(BY_KIND);
    }

    /** Cells ordered by how many incidents happened there. */
    public static synchronized List<Map.Entry<String, Long>> topCells(int n) {
        List<Map.Entry<String, Long>> all = new ArrayList<>(BY_CELL.entrySet());
        all.sort(Map.Entry.<String, Long>comparingByValue(Comparator.reverseOrder()));
        return new ArrayList<>(all.subList(0, Math.min(n, all.size())));
    }
}
