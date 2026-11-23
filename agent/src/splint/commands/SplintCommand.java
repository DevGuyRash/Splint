package splint.commands;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import splint.agent.SplintAgent;
import splint.animals.AnimalIds;
import splint.animals.AnimalOps;
import splint.animals.AnimalOps.Found;
import splint.animals.AnimalOps.Place;
import splint.animals.Incidents;
import splint.animals.Quarantine;
import splint.animals.Stats;
import splint.core.Log;
import zombie.characters.Capability;
import zombie.characters.IsoPlayer;
import zombie.characters.Role;
import zombie.characters.animals.IsoAnimal;
import zombie.commands.CommandArgs;
import zombie.commands.CommandBase;
import zombie.commands.CommandHelp;
import zombie.commands.CommandName;
import zombie.commands.RequiredCapability;
import zombie.core.raknet.UdpConnection;
import zombie.vehicles.BaseVehicle;

/**
 * {@code /splint ...}: animal diagnostics and clean-up for admins, from the server console,
 * RCON or in-game chat. Replies go only to the admin who ran the command; nothing is ever
 * broadcast to players. Destructive forms are dry runs unless they end with {@code confirm},
 * and every change is written to the Splint log with the admin's name.
 */
@CommandName(name = "splint")
@CommandArgs(varArgs = true)
@CommandHelp(helpText = "Splint fixes and admin tools. /splint help", shouldTranslated = false)
@RequiredCapability(requiredCapability = Capability.AnimalCheats)
public class SplintCommand extends CommandBase {
    private static final int LIST_LIMIT = 25;
    private static final int PLAYER_LIST_LIMIT = 60;

    public SplintCommand(String username, Role userRole, String command, UdpConnection connection) {
        super(username, userRole, command, connection);
    }

    static final String HELP = String.join("\n",
            "Splint " + SplintAgent.VERSION + " (destructive commands are dry runs until you add 'confirm'):",
            "/splint status                      patch health, ID counter, counters since start",
            "/splint problems [N]                what Splint handled: by kind, worst cells (with who is there), last N",
            "/splint players [SELECTION]         online players: position, map cell, animals around them",
            "/splint animals census [SELECTION]  counts by map cell, place, type and stage; ghosts, duplicates, frozen",
            "/splint animals list [SELECTION]    individual animals: ID, type, stage, place, coordinates",
            "/splint animals find ID             where the animal with this ID is",
            "/splint animals kill SELECTION [confirm]     remove animals (default places: world, hutch, trailer)",
            "/splint animals fixids [SELECTION] [confirm] give duplicate IDs fresh unique ones",
            "/splint animals frozen [release|kill] [confirm]",
            "SELECTION terms combine, e.g.: cell 42 36 type chicken stage baby except hutch",
            Selector.HELP);

    static final class Usage extends RuntimeException {
        Usage(String m) { super(m); }
    }

    @Override
    protected String Command() {
        List<String> a = new ArrayList<>();
        for (int i = 0; i < getCommandArgsCount(); i++) a.add(getCommandArg(i));
        String result;
        try {
            result = dispatch(a);
        } catch (Usage u) {
            result = u.getMessage();
        } catch (Throwable t) {
            Log.error("/splint " + String.join(" ", a) + " failed", t);
            result = "Splint command failed: " + t + " (details in the Splint log)";
        }
        // In-game chat breaks lines on " <LINE> " (as vanilla /help does); the console on \n.
        return connection == null ? result : result.replace("\n", " <LINE> ");
    }

    private String dispatch(List<String> a) {
        if (a.isEmpty() || a.get(0).equalsIgnoreCase("help")) return HELP;
        String verb = a.get(0).toLowerCase(Locale.ROOT);
        List<String> rest = new ArrayList<>(a.subList(1, a.size()));
        return switch (verb) {
            case "status" -> status();
            case "problems", "incidents" -> problems(rest);
            case "players" -> players(rest);
            case "animals", "animal", "a" -> animals(rest);
            default -> throw new Usage("Unknown /splint command '" + a.get(0) + "'.\n" + HELP);
        };
    }

    private String executor() {
        return connection == null ? null : getExecutorUsername();
    }

    /**
     * World changes must run on the main thread. Console, RCON and chat commands do; the
     * Discord bridge calls in from its own thread, so it gets read-only commands only.
     */
    private static void requireMainThread() {
        Thread main = zombie.network.GameServer.mainThread;
        if (main != null && Thread.currentThread() != main) {
            throw new Usage("This command changes the world; run it from the server console, RCON or in-game chat.");
        }
    }

    // --- status, problems, players ----------------------------------------------------------

    private String status() {
        var missing = SplintAgent.missing();
        return "Splint " + SplintAgent.VERSION + ": " + SplintAgent.APPLIED.size() + " of " + SplintAgent.total()
                + " patches applied" + (missing.isEmpty() ? ", none missing" : ", MISSING " + missing)
                + "\n" + AnimalIds.status() + "\n" + Stats.summary() + "\nfrozen animals: " + Quarantine.frozen().size();
    }

    private String problems(List<String> r) {
        int n = r.isEmpty() ? 10 : Selector.parse(r.get(0), "N");
        Map<String, Long> kinds = Incidents.byKind();
        if (kinds.isEmpty()) return "No animal problems since the server started.";
        StringBuilder b = new StringBuilder("Animal problems handled since start: ").append(kinds);
        b.append("\nWorst map cells:");
        for (var e : Incidents.topCells(5)) {
            String[] xy = e.getKey().split(",");
            List<String> who = AnimalOps.playersInCell(Integer.parseInt(xy[0]), Integer.parseInt(xy[1]));
            b.append("\n  cell ").append(e.getKey()).append(": ").append(e.getValue()).append(" (apop_")
                    .append(xy[0]).append('_').append(xy[1]).append(".bin) players there now: ")
                    .append(who.isEmpty() ? "none" : String.join(",", who));
        }
        b.append("\nLatest ").append(n).append(':');
        for (Incidents.Incident i : Incidents.recent(n)) b.append("\n  ").append(i.line());
        return b.toString();
    }

    private String players(List<String> r) {
        Selector s = r.isEmpty() ? null : Selector.parse(r, executor(), false);
        List<IsoPlayer> online = AnimalOps.players();
        if (online.isEmpty()) return "No players online.";
        List<Found> animals = AnimalOps.all(null);
        StringBuilder b = new StringBuilder(online.size() + " players online");
        if (s != null) b.append(", showing those in: ").append(s.label());
        int shown = 0;
        for (IsoPlayer p : online) {
            int px = (int) p.getX(), py = (int) p.getY(), pz = (int) p.getZ();
            if (s != null && !s.coversPoint(px, py, pz)) continue;
            Map<Place, Integer> near = new java.util.EnumMap<>(Place.class);
            for (Found f : animals) {
                if (Math.abs(f.x() - px) <= 30 && Math.abs(f.y() - py) <= 30) near.merge(f.place(), 1, Integer::sum);
            }
            if (shown++ < PLAYER_LIST_LIMIT) {
                b.append('\n').append(p.getUsername()).append(" at ").append(px).append(',').append(py).append(',').append(pz)
                        .append(" cell ").append(Math.floorDiv(px, AnimalOps.CELL)).append(',')
                        .append(Math.floorDiv(py, AnimalOps.CELL)).append(" animals within 30: ")
                        .append(near.isEmpty() ? "none" : near.toString());
            }
        }
        if (shown > PLAYER_LIST_LIMIT) b.append("\n... ").append(shown - PLAYER_LIST_LIMIT).append(" more");
        return b.toString();
    }

    // --- animals ------------------------------------------------------------------------------

    private String animals(List<String> a) {
        if (a.isEmpty()) return HELP;
        String verb = a.get(0).toLowerCase(Locale.ROOT);
        List<String> rest = new ArrayList<>(a.subList(1, a.size()));
        boolean confirm = rest.removeIf(s -> s.equalsIgnoreCase("confirm"));
        return switch (verb) {
            case "census" -> census(Selector.parse(rest, executor(), false));
            case "list" -> list(Selector.parse(rest, executor(), false));
            case "find" -> find(rest);
            case "kill", "remove" -> {
                if (rest.isEmpty()) throw new Usage("kill needs a SELECTION ('all' means every loaded animal).\n" + Selector.HELP);
                yield kill(Selector.parse(rest, executor(), true), confirm);
            }
            case "fixids" -> fixIds(Selector.parse(rest, executor(), false), confirm);
            case "frozen" -> frozen(rest, confirm);
            default -> throw new Usage("Unknown animals command '" + a.get(0) + "'.\n" + HELP);
        };
    }

    static String where(Found f) {
        String at = f.x() + "," + f.y() + "," + f.z() + " cell " + f.cellX() + "," + f.cellY();
        return switch (f.place()) {
            case WORLD -> "map " + at;
            case HUTCH -> "hutch " + at;
            case TRAILER -> "trailer #" + ((BaseVehicle) f.holder()).getId() + " " + at;
            case INVENTORY -> "carried by " + Selector.carrier(f) + " " + at;
        };
    }

    private static String line(Found f) {
        IsoAnimal a = f.animal();
        String frozen = Quarantine.isFrozen(a) ? " FROZEN(" + Quarantine.lastError(a) + ")" : "";
        String stage = AnimalOps.isGhost(a) ? "" : AnimalOps.isJuvenile(a) ? " baby" : " adult";
        return AnimalOps.brief(a) + stage + " - " + where(f) + frozen;
    }

    private String census(Selector s) {
        List<Found> found = s.run();
        Map<String, Map<String, Integer>> byCell = new TreeMap<>();
        Map<Place, Integer> byPlace = new java.util.EnumMap<>(Place.class);
        Map<Integer, Integer> ids = new HashMap<>();
        int ghosts = 0, frozen = 0, babies = 0;
        for (Found f : found) {
            IsoAnimal a = f.animal();
            boolean ghost = AnimalOps.isGhost(a);
            if (ghost) {
                ghosts++;
            } else {
                ids.merge(a.getAnimalID(), 1, Integer::sum);
                if (AnimalOps.isJuvenile(a)) babies++;
            }
            if (Quarantine.isFrozen(a)) frozen++;
            byPlace.merge(f.place(), 1, Integer::sum);
            byCell.computeIfAbsent(f.cellX() + "," + f.cellY(), k -> new TreeMap<>())
                    .merge(ghost ? "GHOST" : a.getAnimalType(), 1, Integer::sum);
        }
        long dupGroups = ids.values().stream().filter(n -> n > 1).count();
        StringBuilder b = new StringBuilder("Census of ").append(s.label()).append(": ").append(found.size())
                .append(" animals ").append(byPlace).append(", babies ").append(babies).append(", ghosts ").append(ghosts)
                .append(", frozen ").append(frozen).append(", duplicate-ID groups ").append(dupGroups);
        List<Map.Entry<String, Map<String, Integer>>> cells = new ArrayList<>(byCell.entrySet());
        cells.sort(Comparator.comparingInt(e -> -e.getValue().values().stream().mapToInt(Integer::intValue).sum()));
        for (int i = 0; i < cells.size() && i < LIST_LIMIT; i++) {
            var e = cells.get(i);
            int total = e.getValue().values().stream().mapToInt(Integer::intValue).sum();
            String[] xy = e.getKey().split(",");
            List<String> who = AnimalOps.playersInCell(Integer.parseInt(xy[0]), Integer.parseInt(xy[1]));
            b.append("\ncell ").append(e.getKey()).append(": ").append(total).append(' ').append(e.getValue())
                    .append(" players: ").append(who.isEmpty() ? "none" : String.join(",", who));
        }
        if (cells.size() > LIST_LIMIT) b.append("\n... ").append(cells.size() - LIST_LIMIT).append(" more cells (see the Splint log)");
        Log.info("/splint census by " + getExecutorUsername() + ": " + b.toString().replace('\n', '|'));
        return b.toString();
    }

    private String list(Selector s) {
        List<Found> found = s.run();
        StringBuilder b = new StringBuilder(s.label()).append(": ").append(found.size()).append(" animals");
        for (int i = 0; i < found.size() && i < LIST_LIMIT; i++) b.append('\n').append(line(found.get(i)));
        if (found.size() > LIST_LIMIT) b.append("\n... ").append(found.size() - LIST_LIMIT).append(" more (full list in the Splint log)");
        StringBuilder full = new StringBuilder();
        for (Found f : found) full.append("\n  ").append(AnimalOps.describe(f.animal())).append(" place=").append(where(f));
        Log.info("/splint list " + s.label() + " by " + getExecutorUsername() + ":" + full);
        return b.toString();
    }

    private String find(List<String> r) {
        if (r.isEmpty()) throw new Usage("find needs an animal ID");
        int id = Selector.parse(r.get(0), "ID");
        List<Found> found = Selector.parse(List.of("id", String.valueOf(id)), executor(), false).run();
        if (found.isEmpty()) return "No loaded animal has id " + id + " (animals in unloaded areas are not in memory).";
        StringBuilder b = new StringBuilder();
        for (Found f : found) b.append(line(f)).append("\n  ").append(AnimalOps.describe(f.animal())).append('\n');
        if (found.size() > 1) {
            b.append("WARNING: ").append(found.size()).append(" animals share this id; /splint animals fixids id ").append(id);
        }
        return b.toString().stripTrailing();
    }

    private String kill(Selector s, boolean confirm) {
        List<Found> found = s.run();
        if (found.isEmpty()) return "Nothing matches " + s.label() + ".";
        Map<String, Integer> types = new TreeMap<>();
        Map<Place, Integer> places = new java.util.EnumMap<>(Place.class);
        for (Found f : found) {
            types.merge(AnimalOps.isGhost(f.animal()) ? "GHOST" : f.animal().getAnimalType(), 1, Integer::sum);
            places.merge(f.place(), 1, Integer::sum);
        }
        if (!confirm) {
            return "Dry run: would remove " + found.size() + " animals (" + s.label() + ") " + types + " " + places
                    + ". Repeat with 'confirm' at the end to remove them.";
        }
        requireMainThread();
        int removed = 0;
        List<String> failed = new ArrayList<>();
        StringBuilder audit = new StringBuilder();
        for (Found f : found) {
            String what = AnimalOps.describe(f.animal()) + " place=" + where(f);
            String err = AnimalOps.kill(f);
            if (err == null) {
                removed++;
                audit.append("\n  removed ").append(what);
            } else {
                failed.add(f.animal().getAnimalID() + ": " + err);
            }
        }
        Log.info("/splint kill " + s.label() + " by " + getExecutorUsername() + ": removed " + removed + " of " + found.size() + audit);
        return "Removed " + removed + " of " + found.size() + " animals (" + s.label() + ") " + types + " " + places
                + (failed.isEmpty() ? "" : "; failed: " + failed);
    }

    private String fixIds(Selector s, boolean confirm) {
        Map<Integer, List<Found>> byId = new HashMap<>();
        for (Found f : AnimalOps.all(null)) {
            if (!AnimalOps.isGhost(f.animal())) byId.computeIfAbsent(f.animal().getAnimalID(), k -> new ArrayList<>()).add(f);
        }
        Map<IsoAnimal, Boolean> selected = new IdentityHashMap<>();
        for (Found f : s.run()) selected.put(f.animal(), true);
        List<Found> toFix = new ArrayList<>();
        for (List<Found> group : byId.values()) {
            if (group.size() < 2) continue;
            // Babies find their mother by ID: the animal with babies keeps the ID.
            group.sort(Comparator.comparingInt((Found f) -> -babies(f.animal()).size()));
            for (Found f : group.subList(1, group.size())) if (selected.containsKey(f.animal())) toFix.add(f);
        }
        if (toFix.isEmpty()) return "No duplicate animal IDs among the selected loaded animals.";
        if (!confirm) {
            return "Dry run: " + toFix.size() + " selected animals share an ID with another loaded animal. "
                    + "Add 'confirm' to give each a fresh unique ID (in each group the animal with babies keeps its ID).";
        }
        requireMainThread();
        StringBuilder audit = new StringBuilder();
        for (Found f : toFix) {
            int old = f.animal().getAnimalID();
            int fresh = AnimalIds.allocate();
            f.animal().setAnimalID(fresh);
            for (IsoAnimal baby : babies(f.animal())) {
                if (baby.motherId == old) baby.motherId = fresh;
                if (baby.attachBackToMother == old) baby.attachBackToMother = fresh;
            }
            Stats.duplicateIdsFixed.incrementAndGet();
            audit.append("\n  ").append(old).append(" -> ").append(fresh).append(' ').append(where(f));
        }
        Log.info("/splint fixids by " + getExecutorUsername() + ":" + audit);
        return "Gave " + toFix.size() + " animals fresh unique IDs (details in the Splint log).";
    }

    private static List<IsoAnimal> babies(IsoAnimal a) {
        List<IsoAnimal> b = a.getBabies();
        return b == null ? List.of() : new ArrayList<>(b);
    }

    private String frozen(List<String> r, boolean confirm) {
        List<IsoAnimal> frozen = Quarantine.frozen();
        if (frozen.isEmpty()) return "No frozen animals.";
        String action = r.isEmpty() ? "list" : r.get(0).toLowerCase(Locale.ROOT);
        Map<IsoAnimal, Found> places = new IdentityHashMap<>();
        for (Found f : AnimalOps.all(null)) places.put(f.animal(), f);
        switch (action) {
            case "release" -> {
                if (!confirm) return "Dry run: would release " + frozen.size() + " frozen animals. Add 'confirm'.";
                requireMainThread();
                frozen.forEach(Quarantine::release);
                Log.info("/splint frozen release by " + getExecutorUsername() + ": " + frozen.size());
                return "Released " + frozen.size() + " animals; they will be updated again.";
            }
            case "kill" -> {
                if (!confirm) return "Dry run: would remove " + frozen.size() + " frozen animals. Add 'confirm'.";
                requireMainThread();
                int n = 0;
                for (IsoAnimal a : frozen) {
                    Found f = places.getOrDefault(a, new Found(a, Place.WORLD, null, (int) a.getX(), (int) a.getY(), (int) a.getZ()));
                    Log.info("/splint frozen kill by " + getExecutorUsername() + ": " + AnimalOps.describe(a));
                    if (AnimalOps.kill(f) == null) n++;
                    Quarantine.release(a);
                }
                return "Removed " + n + " frozen animals.";
            }
            default -> {
                StringBuilder b = new StringBuilder(frozen.size() + " frozen animals:");
                for (int i = 0; i < frozen.size() && i < LIST_LIMIT; i++) {
                    IsoAnimal a = frozen.get(i);
                    Found f = places.get(a);
                    b.append('\n').append(f != null ? line(f) : AnimalOps.describe(a));
                }
                return b.toString();
            }
        }
    }
}
