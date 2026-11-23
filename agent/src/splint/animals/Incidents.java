package splint.animals;

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
import zombie.characters.IsoPlayer;
import zombie.characters.animals.IsoAnimal;

/**
 * Every problem Splint handled since the server started, with where it happened. On a server
 * with 100+ players nobody knows in advance which cell or player is involved; this is what
 * {@code /splint problems} reads to answer "where is it happening, and who is there".
 */
public final class Incidents {
    private static final int KEEP = 500;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    public record Incident(String time, String kind, int x, int y, int z, String animal, String nearestPlayer,
                           List<String> playersInCell) {
        public int cellX() { return Math.floorDiv(x, AnimalOps.CELL); }
        public int cellY() { return Math.floorDiv(y, AnimalOps.CELL); }

        public String line() {
            return time + " " + kind + " at " + x + "," + y + "," + z + " cell " + cellX() + "," + cellY() + " " + animal
                    + (nearestPlayer.isEmpty() ? "" : " nearest " + nearestPlayer)
                    + (playersInCell.isEmpty() ? " (no player in cell)" : " in cell: " + String.join(",", playersInCell));
        }
    }

    private static final Deque<Incident> RECENT = new ArrayDeque<>();
    private static final Map<String, Long> BY_KIND = new TreeMap<>();
    private static final Map<String, Long> BY_CELL = new HashMap<>();

    private Incidents() {}

    public static void record(String kind, IsoAnimal a) {
        try {
            record(kind, (int) Math.floor(a.getX()), (int) Math.floor(a.getY()), (int) Math.floor(a.getZ()), AnimalOps.brief(a));
        } catch (Throwable ignored) {
            // recording must never add a failure of its own
        }
    }

    public static void record(String kind, int x, int y, int z, String what) {
        IsoPlayer near = AnimalOps.nearestPlayer(x, y);
        String nearest = near == null ? "" : near.getUsername() + "@" + (int) Math.hypot(near.getX() - x, near.getY() - y);
        Incident i = new Incident(LocalTime.now().format(TIME), kind, x, y, z, what, nearest,
                AnimalOps.playersInCell(Math.floorDiv(x, AnimalOps.CELL), Math.floorDiv(y, AnimalOps.CELL)));
        synchronized (Incidents.class) {
            RECENT.addFirst(i);
            while (RECENT.size() > KEEP) RECENT.removeLast();
            BY_KIND.merge(kind, 1L, Long::sum);
            BY_CELL.merge(i.cellX() + "," + i.cellY(), 1L, Long::sum);
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
