package splint.commands;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Predicate;
import splint.agent.SplintAgent;
import splint.animals.AnimalIds;
import splint.animals.AnimalOps;
import splint.animals.AnimalOps.Area;
import splint.animals.AnimalOps.Found;
import splint.animals.AnimalOps.Place;
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
 * {@code /splint ...}: animal diagnostics and clean-up for admins. Works from the server
 * console, RCON and in-game chat. Every destructive form is a dry run unless it ends with
 * {@code confirm}; every action is written to the Splint log with the admin's name.
 */
@CommandName(name = "splint")
@CommandArgs(varArgs = true)
@CommandHelp(helpText = "Splint fixes and admin tools. /splint help", shouldTranslated = false)
@RequiredCapability(requiredCapability = Capability.AnimalCheats)
public class SplintCommand extends CommandBase {
    private static final int LIST_LIMIT = 25;
    private static final int PLAYER_SCAN_RADIUS = 120;

    public SplintCommand(String username, Role userRole, String command, UdpConnection connection) {
        super(username, userRole, command, connection);
    }

    static final String HELP = String.join("\n",
            "Splint " + SplintAgent.VERSION + " commands (destructive ones are dry runs until you add 'confirm'):",
            "/splint status                         patch health, ID counter, counters",
            "/splint animals census [AREA]          animals by map cell and type, ghosts, duplicate IDs, frozen",
            "/splint animals list AREA [TYPE]       individual animals with ID, place and coordinates",
            "/splint animals find ID                where the animal with this ID is",
            "/splint animals kill AREA [TYPE] [confirm]   remove animals",
            "/splint animals fixids [confirm]       give every duplicate animal ID a fresh unique one",
            "/splint animals frozen [release|kill] [confirm]  animals frozen after repeated errors",
            "AREA: cell CX CY | near X Y R | me R | hutch X Y Z | hutches cell CX CY | trailer VEHICLE_ID",
            "      | trailers near X Y R | inventory USERNAME | ghosts | id ID | all",
            "TYPE: an animal type (hen, chick, cow...), a group (chicken, cow, pig...), or ghost",
            "Map cell = floor(x/256), floor(y/256); it is also the apop_CX_CY.bin population file.");

    @Override
    protected String Command() {
        String[] a = args();
        try {
            if (a.length == 0 || a[0].equalsIgnoreCase("help")) return HELP;
            String result = switch (a[0].toLowerCase(Locale.ROOT)) {
                case "status" -> status();
                case "animals", "animal", "a" -> animals(a);
                default -> "Unknown /splint command '" + a[0] + "'.\n" + HELP;
            };
            return result;
        } catch (Usage u) {
            return u.getMessage();
        } catch (Throwable t) {
            Log.error("/splint " + String.join(" ", a) + " failed", t);
            return "Splint command failed: " + t + " (details in the Splint log)";
        }
    }

    private String[] args() {
        String[] out = new String[getCommandArgsCount()];
        for (int i = 0; i < out.length; i++) out[i] = getCommandArg(i);
        return out;
    }

    private static final class Usage extends RuntimeException {
        Usage(String m) { super(m); }
    }

    // --- status ---------------------------------------------------------------------------

    private String status() {
        StringBuilder b = new StringBuilder("Splint ").append(SplintAgent.VERSION).append(": ")
                .append(SplintAgent.APPLIED.size()).append(" methods patched");
        var missing = SplintAgent.missing();
        b.append(missing.isEmpty() ? ", none missing" : ", MISSING " + missing);
        b.append('\n').append(AnimalIds.status());
        b.append('\n').append(Stats.summary());
        b.append("\nfrozen animals: ").append(Quarantine.frozen().size());
        return b.toString();
    }

    // --- animals --------------------------------------------------------------------------

    private String animals(String[] a) {
        if (a.length < 2) return HELP;
        String verb = a[1].toLowerCase(Locale.ROOT);
        List<String> rest = new ArrayList<>(List.of(a).subList(2, a.length));
        boolean confirm = rest.removeIf(s -> s.equalsIgnoreCase("confirm"));
        return switch (verb) {
            case "census" -> census(rest.isEmpty() ? new Selection(null, f -> true, "all loaded animals") : select(rest));
            case "list" -> list(select(rest));
            case "find" -> find(rest);
            case "kill", "remove" -> kill(select(rest), confirm);
            case "fixids" -> fixIds(confirm);
            case "frozen" -> frozen(rest, confirm);
            default -> throw new Usage("Unknown animals command '" + a[1] + "'.\n" + HELP);
        };
    }

    /** An area to scan plus a filter over what is found there. */
    private static class Selection {
        private final Area scan;
        private final Predicate<Found> filter;
        private final String label;

        Selection(Area scan, Predicate<Found> filter, String label) {
            this.scan = scan;
            this.filter = filter;
            this.label = label;
        }

        Area scan() { return scan; }
        Predicate<Found> filter() { return filter; }
        String label() { return label; }

        List<Found> run() {
            List<Found> out = new ArrayList<>();
            for (Found f : AnimalOps.all(scan)) if (filter.test(f)) out.add(f);
            out.sort(Comparator.comparingInt(Found::x).thenComparingInt(Found::y));
            return out;
        }
    }

    private Selection select(List<String> r) {
        if (r.isEmpty()) throw new Usage("Missing AREA.\n" + HELP);
        String kind = r.get(0).toLowerCase(Locale.ROOT);
        Selection base;
        int used;
        switch (kind) {
            case "cell" -> {
                int cx = num(r, 1), cy = num(r, 2);
                base = new Selection(Area.cell(cx, cy), f -> true, "map cell " + cx + "," + cy);
                used = 3;
            }
            case "near" -> {
                int x = num(r, 1), y = num(r, 2), rad = Math.min(num(r, 3), 300);
                base = new Selection(Area.radius(x, y, rad), f -> true, "within " + rad + " of " + x + "," + y);
                used = 4;
            }
            case "me" -> {
                IsoPlayer p = self();
                int rad = Math.min(num(r, 1), 300);
                base = new Selection(Area.radius((int) p.getX(), (int) p.getY(), rad), f -> true,
                        "within " + rad + " of " + getExecutorUsername());
                used = 2;
            }
            case "hutch" -> {
                int x = num(r, 1), y = num(r, 2), z = num(r, 3);
                base = new Selection(null, f -> f.place() == Place.HUTCH && f.x() == x && f.y() == y && f.z() == z,
                        "hutch at " + x + "," + y + "," + z);
                used = 4;
            }
            case "hutches" -> {
                if (r.size() < 2 || !r.get(1).equalsIgnoreCase("cell")) throw new Usage("Use: hutches cell CX CY");
                int cx = num(r, 2), cy = num(r, 3);
                Area cell = Area.cell(cx, cy);
                base = new Selection(null, f -> f.place() == Place.HUTCH && cell.contains(f.x(), f.y(), f.z()),
                        "hutches in map cell " + cx + "," + cy);
                used = 4;
            }
            case "trailer" -> {
                int id = num(r, 1);
                base = new Selection(null, f -> f.place() == Place.TRAILER && ((BaseVehicle) f.holder()).getId() == id,
                        "trailer vehicle " + id);
                used = 2;
            }
            case "trailers" -> {
                if (r.size() < 2 || !r.get(1).equalsIgnoreCase("near")) throw new Usage("Use: trailers near X Y R");
                int x = num(r, 2), y = num(r, 3), rad = num(r, 4);
                Area area = Area.radius(x, y, rad);
                base = new Selection(null, f -> f.place() == Place.TRAILER && area.contains(f.x(), f.y(), f.z()),
                        "trailers within " + rad + " of " + x + "," + y);
                used = 5;
            }
            case "inventory" -> {
                if (r.size() < 2) throw new Usage("Use: inventory USERNAME");
                String user = r.get(1);
                base = new Selection(null, f -> f.place() == Place.INVENTORY && holderName(f).equalsIgnoreCase(user),
                        "carried by " + user);
                used = 2;
            }
            case "ghosts", "ghost" -> {
                base = new Selection(null, f -> AnimalOps.isGhost(f.animal()), "uninitialised (ghost) animals");
                // Ghosts can be referenced only by a square, so scan around every player too.
                return withPlayerSquares(base);
            }
            case "id" -> {
                int id = num(r, 1);
                base = new Selection(null, f -> f.animal().getAnimalID() == id, "animal id " + id);
                used = 2;
            }
            case "all" -> {
                base = new Selection(null, f -> true, "all loaded animals");
                used = 1;
            }
            default -> throw new Usage("Unknown AREA '" + r.get(0) + "'.\n" + HELP);
        }
        if (r.size() > used) {
            String type = r.get(used);
            Predicate<Found> t = AnimalOps.ofType(type);
            Predicate<Found> f = base.filter();
            return new Selection(base.scan(), x -> f.test(x) && t.test(x), base.label() + ", type " + type);
        }
        return base;
    }

    private Selection withPlayerSquares(Selection base) {
        return new Selection(null, base.filter(), base.label()) {
            @Override
            List<Found> run() {
                Map<IsoAnimal, Found> all = new java.util.IdentityHashMap<>();
                for (Found f : AnimalOps.all(null)) all.put(f.animal(), f);
                for (IsoPlayer p : AnimalOps.players()) {
                    for (Found f : AnimalOps.all(Area.radius((int) p.getX(), (int) p.getY(), PLAYER_SCAN_RADIUS))) {
                        all.putIfAbsent(f.animal(), f);
                    }
                }
                List<Found> out = new ArrayList<>();
                for (Found f : all.values()) if (base.filter().test(f)) out.add(f);
                return out;
            }
        };
    }

    private IsoPlayer self() {
        if (connection == null) throw new Usage("'me' needs an in-game admin; use 'near X Y R' from the console.");
        for (IsoPlayer p : AnimalOps.players()) if (p.getUsername().equalsIgnoreCase(getExecutorUsername())) return p;
        throw new Usage("Cannot find your character.");
    }

    private static int num(List<String> r, int i) {
        if (r.size() <= i) throw new Usage("Missing number at position " + (i + 1) + ".\n" + HELP);
        try {
            return Integer.parseInt(r.get(i));
        } catch (NumberFormatException e) {
            throw new Usage("Not a number: '" + r.get(i) + "'");
        }
    }

    private static String holderName(Found f) {
        for (IsoPlayer p : AnimalOps.players()) {
            try {
                if (p.getInventory().getAllTypeRecurse("Animal").contains(f.holder())) return p.getUsername();
            } catch (Throwable ignored) {
                // inventory not readable; skip this player
            }
        }
        return "";
    }

    private static String where(Found f) {
        String at = f.x() + "," + f.y() + "," + f.z() + " cell " + f.cellX() + "," + f.cellY();
        return switch (f.place()) {
            case WORLD -> "map " + at;
            case HUTCH -> "hutch " + at;
            case TRAILER -> "trailer #" + ((BaseVehicle) f.holder()).getId() + " " + at;
            case INVENTORY -> "inventory of " + holderName(f) + " " + at;
        };
    }

    private static String line(Found f) {
        IsoAnimal a = f.animal();
        String type = AnimalOps.isGhost(a) ? "GHOST" : a.getAnimalType();
        String frozen = Quarantine.isFrozen(a) ? " FROZEN(" + Quarantine.lastError(a) + ")" : "";
        return "id " + a.getAnimalID() + " " + type + " - " + where(f) + frozen;
    }

    private String census(Selection s) {
        List<Found> found = s.run();
        Map<String, Map<String, Integer>> byCell = new TreeMap<>();
        Map<Integer, Integer> ids = new HashMap<>();
        int ghosts = 0, frozen = 0;
        Map<Place, Integer> byPlace = new LinkedHashMap<>();
        for (Found f : found) {
            IsoAnimal a = f.animal();
            boolean ghost = AnimalOps.isGhost(a);
            if (ghost) ghosts++;
            if (Quarantine.isFrozen(a)) frozen++;
            byPlace.merge(f.place(), 1, Integer::sum);
            String cell = f.cellX() + "," + f.cellY();
            byCell.computeIfAbsent(cell, k -> new TreeMap<>()).merge(ghost ? "GHOST" : a.getAnimalType(), 1, Integer::sum);
            if (!ghost) ids.merge(a.getAnimalID(), 1, Integer::sum);
        }
        long dupGroups = ids.values().stream().filter(n -> n > 1).count();
        StringBuilder b = new StringBuilder("Census of ").append(s.label()).append(": ").append(found.size())
                .append(" animals ").append(byPlace).append(", ghosts ").append(ghosts).append(", frozen ")
                .append(frozen).append(", duplicate-ID groups ").append(dupGroups);
        int shown = 0;
        for (var e : byCell.entrySet()) {
            if (shown++ == LIST_LIMIT) {
                b.append("\n... ").append(byCell.size() - LIST_LIMIT).append(" more cells (see Splint log)");
                break;
            }
            int total = e.getValue().values().stream().mapToInt(Integer::intValue).sum();
            b.append("\ncell ").append(e.getKey()).append(": ").append(total).append(' ').append(e.getValue());
        }
        Log.info("/splint census by " + getExecutorUsername() + ": " + b.toString().replace('\n', '|'));
        return b.toString();
    }

    private String list(Selection s) {
        List<Found> found = s.run();
        StringBuilder b = new StringBuilder(s.label()).append(": ").append(found.size()).append(" animals");
        for (int i = 0; i < found.size() && i < LIST_LIMIT; i++) b.append('\n').append(line(found.get(i)));
        if (found.size() > LIST_LIMIT) b.append("\n... ").append(found.size() - LIST_LIMIT).append(" more (full list in Splint log)");
        StringBuilder full = new StringBuilder();
        for (Found f : found) full.append("\n  ").append(AnimalOps.describe(f.animal())).append(" place=").append(where(f));
        Log.info("/splint list " + s.label() + " by " + getExecutorUsername() + ":" + full);
        return b.toString();
    }

    private String find(List<String> r) {
        int id = num(r, 0);
        List<Found> found = new Selection(null, f -> f.animal().getAnimalID() == id, "id " + id).run();
        if (found.isEmpty()) return "No loaded animal has id " + id + " (animals in unloaded areas are not in memory).";
        StringBuilder b = new StringBuilder();
        for (Found f : found) b.append(line(f)).append('\n').append("  ").append(AnimalOps.describe(f.animal())).append('\n');
        if (found.size() > 1) b.append("WARNING: ").append(found.size()).append(" animals share this id; /splint animals fixids");
        return b.toString().stripTrailing();
    }

    private String kill(Selection s, boolean confirm) {
        List<Found> found = s.run();
        if (found.isEmpty()) return "Nothing matches " + s.label() + ".";
        Map<String, Integer> types = new TreeMap<>();
        for (Found f : found) types.merge(AnimalOps.isGhost(f.animal()) ? "GHOST" : f.animal().getAnimalType(), 1, Integer::sum);
        if (!confirm) {
            return "Dry run: would remove " + found.size() + " animals (" + s.label() + ") " + types
                    + ". Repeat the command with 'confirm' at the end to remove them.";
        }
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
        Log.info("/splint kill " + s.label() + " by " + getExecutorUsername() + ": removed " + removed + audit);
        return "Removed " + removed + " of " + found.size() + " animals (" + s.label() + ") " + types
                + (failed.isEmpty() ? "" : "; failed: " + failed);
    }

    private String fixIds(boolean confirm) {
        Map<Integer, List<Found>> byId = new HashMap<>();
        for (Found f : AnimalOps.all(null)) {
            if (!AnimalOps.isGhost(f.animal())) byId.computeIfAbsent(f.animal().getAnimalID(), k -> new ArrayList<>()).add(f);
        }
        List<Found> toFix = new ArrayList<>();
        for (List<Found> group : byId.values()) if (group.size() > 1) toFix.addAll(group.subList(1, group.size()));
        if (toFix.isEmpty()) return "No duplicate animal IDs among loaded animals.";
        if (!confirm) return "Dry run: " + toFix.size() + " animals share an ID with another loaded animal. "
                + "Add 'confirm' to give each a fresh unique ID (the first of each group keeps its ID).";
        StringBuilder audit = new StringBuilder();
        for (Found f : toFix) {
            int old = f.animal().getAnimalID();
            int fresh = AnimalIds.allocate();
            f.animal().setAnimalID(fresh);
            Stats.duplicateIdsFixed.incrementAndGet();
            audit.append("\n  ").append(old).append(" -> ").append(fresh).append(' ').append(where(f));
        }
        Log.info("/splint fixids by " + getExecutorUsername() + ":" + audit);
        return "Gave " + toFix.size() + " animals fresh unique IDs (details in Splint log).";
    }

    private String frozen(List<String> r, boolean confirm) {
        List<IsoAnimal> frozen = Quarantine.frozen();
        if (frozen.isEmpty()) return "No frozen animals.";
        String action = r.isEmpty() ? "list" : r.get(0).toLowerCase(Locale.ROOT);
        Map<IsoAnimal, Found> places = new java.util.IdentityHashMap<>();
        for (Found f : AnimalOps.all(null)) places.put(f.animal(), f);
        switch (action) {
            case "release" -> {
                if (!confirm) return "Dry run: would release " + frozen.size() + " frozen animals. Add 'confirm'.";
                frozen.forEach(Quarantine::release);
                Log.info("/splint frozen release by " + getExecutorUsername() + ": " + frozen.size());
                return "Released " + frozen.size() + " animals; they will be updated again.";
            }
            case "kill" -> {
                if (!confirm) return "Dry run: would remove " + frozen.size() + " frozen animals. Add 'confirm'.";
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
