package splint.animals;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import splint.animals.AnimalOps.Found;
import splint.animals.AnimalOps.Place;
import splint.core.command.Args;
import splint.core.command.CommandContext;
import splint.core.command.ModuleCommand;
import splint.core.command.Usage;
import splint.core.log.Log;
import splint.core.world.World;
import zombie.characters.animals.IsoAnimal;
import zombie.vehicles.BaseVehicle;

/** {@code /splint animals ...}. Destructive forms are dry runs unless the words include {@code confirm}. */
final class AnimalCommands implements ModuleCommand {
    private static final int LIST_LIMIT = 25;

    @Override
    public String name() { return "animals"; }

    @Override
    public List<String> aliases() { return List.of("animal", "a"); }

    @Override
    public String help() {
        return String.join("\n",
                "/splint animals census [SELECTION]   counts by map cell, place, type and stage; babies, ghosts, duplicates, frozen",
                "/splint animals list [SELECTION]     individual animals: ID, type, stage, place, coordinates",
                "/splint animals find ID              where the animal with this ID is",
                "/splint animals kill SELECTION [confirm]       remove animals (default places: world, hutch, trailer)",
                "/splint animals fixids [SELECTION] [confirm]   give duplicate IDs fresh unique ones",
                "/splint animals frozen [release|kill] [confirm]",
                "SELECTION terms combine, e.g.: cell 42 36 type chicken stage baby except hutch",
                AnimalSelector.HELP);
    }

    @Override
    public String run(CommandContext ctx, Args a) {
        if (!a.more()) return help();
        String verb = a.lower();
        return switch (verb) {
            case "census" -> census(AnimalSelector.parse(a, ctx, false), ctx);
            case "list" -> list(AnimalSelector.parse(a, ctx, false), ctx);
            case "find" -> find(a, ctx);
            case "kill", "remove" -> {
                if (!a.more()) throw new Usage("kill needs a SELECTION ('all' means every loaded animal).\n" + AnimalSelector.HELP);
                boolean confirm = a.confirmed();
                yield kill(AnimalSelector.parse(a, ctx, true), confirm, ctx);
            }
            case "fixids" -> {
                boolean confirm = a.confirmed();
                yield fixIds(AnimalSelector.parse(a, ctx, false), confirm, ctx);
            }
            case "frozen" -> frozen(a, ctx);
            default -> throw new Usage("Unknown animals command '" + verb + "'.\n" + help());
        };
    }

    static String where(Found f) {
        String at = f.x() + "," + f.y() + "," + f.z() + " cell " + f.cellX() + "," + f.cellY();
        return switch (f.place()) {
            case WORLD -> "map " + at;
            case HUTCH -> "hutch " + at;
            case TRAILER -> "trailer #" + ((BaseVehicle) f.holder()).getId() + " " + at;
            case INVENTORY -> "carried by " + AnimalSelector.carrier(f) + " " + at;
        };
    }

    private static String line(Found f) {
        IsoAnimal a = f.animal();
        String frozen = Quarantine.isFrozen(a) ? " FROZEN(" + Quarantine.lastError(a) + ")" : "";
        String stage = AnimalOps.isGhost(a) ? "" : AnimalOps.isJuvenile(a) ? " baby" : " adult";
        return AnimalOps.brief(a) + stage + " - " + where(f) + frozen;
    }

    private static String census(AnimalSelector s, CommandContext ctx) {
        List<Found> found = s.run();
        Map<String, Map<String, Integer>> byCell = new TreeMap<>();
        Map<Place, Integer> byPlace = new EnumMap<>(Place.class);
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
            List<String> who = World.playersInCell(Integer.parseInt(xy[0]), Integer.parseInt(xy[1]));
            b.append("\ncell ").append(e.getKey()).append(": ").append(total).append(' ').append(e.getValue())
                    .append(" players: ").append(who.isEmpty() ? "none" : String.join(",", who));
        }
        if (cells.size() > LIST_LIMIT) b.append("\n... ").append(cells.size() - LIST_LIMIT).append(" more cells (see the Splint log)");
        Log.info("census by " + ctx.who() + ": " + b.toString().replace('\n', '|'));
        return b.toString();
    }

    private static String list(AnimalSelector s, CommandContext ctx) {
        List<Found> found = s.run();
        StringBuilder b = new StringBuilder(s.label()).append(": ").append(found.size()).append(" animals");
        for (int i = 0; i < found.size() && i < LIST_LIMIT; i++) b.append('\n').append(line(found.get(i)));
        if (found.size() > LIST_LIMIT) b.append("\n... ").append(found.size() - LIST_LIMIT).append(" more (full list in the Splint log)");
        StringBuilder full = new StringBuilder();
        for (Found f : found) full.append("\n  ").append(AnimalOps.describe(f.animal())).append(" place=").append(where(f));
        Log.info("list " + s.label() + " by " + ctx.who() + ":" + full);
        return b.toString();
    }

    private static String find(Args a, CommandContext ctx) {
        int id = a.integer("ID");
        List<Found> found = AnimalSelector.parse(new Args(List.of("id", String.valueOf(id))), ctx, false).run();
        if (found.isEmpty()) return "No loaded animal has id " + id + " (animals in unloaded areas are not in memory).";
        StringBuilder b = new StringBuilder();
        for (Found f : found) b.append(line(f)).append("\n  ").append(AnimalOps.describe(f.animal())).append('\n');
        if (found.size() > 1) {
            b.append("WARNING: ").append(found.size()).append(" animals share this id; /splint animals fixids id ").append(id);
        }
        return b.toString().stripTrailing();
    }

    private static String kill(AnimalSelector s, boolean confirm, CommandContext ctx) {
        List<Found> found = s.run();
        if (found.isEmpty()) return "Nothing matches " + s.label() + ".";
        Map<String, Integer> types = new TreeMap<>();
        Map<Place, Integer> places = new EnumMap<>(Place.class);
        for (Found f : found) {
            types.merge(AnimalOps.isGhost(f.animal()) ? "GHOST" : f.animal().getAnimalType(), 1, Integer::sum);
            places.merge(f.place(), 1, Integer::sum);
        }
        if (!confirm) {
            return "Dry run: would remove " + found.size() + " animals (" + s.label() + ") " + types + " " + places
                    + ". Repeat with 'confirm' at the end to remove them.";
        }
        ctx.requireMainThread();
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
        ctx.audit("kill " + s.label() + ": removed " + removed + " of " + found.size() + audit);
        return "Removed " + removed + " of " + found.size() + " animals (" + s.label() + ") " + types + " " + places
                + (failed.isEmpty() ? "" : "; failed: " + failed);
    }

    private static String fixIds(AnimalSelector s, boolean confirm, CommandContext ctx) {
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
        ctx.requireMainThread();
        StringBuilder audit = new StringBuilder();
        // The animal that keeps each ID stays the index's holder of it.
        for (List<Found> group : byId.values()) if (group.size() > 1) AnimalIndex.put(group.get(0).animal().getAnimalID(), group.get(0).animal());
        for (Found f : toFix) {
            int old = f.animal().getAnimalID();
            int fresh = AnimalIds.allocate();
            f.animal().setAnimalID(fresh);
            for (IsoAnimal baby : babies(f.animal())) {
                if (baby.motherId == old) baby.motherId = fresh;
                if (baby.attachBackToMother == old) baby.attachBackToMother = fresh;
            }
            AnimalStats.DUPLICATE_IDS_FIXED.incrementAndGet();
            audit.append("\n  ").append(old).append(" -> ").append(fresh).append(' ').append(where(f));
        }
        ctx.audit("fixids:" + audit);
        return "Gave " + toFix.size() + " animals fresh unique IDs (details in the Splint log).";
    }

    private static List<IsoAnimal> babies(IsoAnimal a) {
        List<IsoAnimal> b = a.getBabies();
        return b == null ? List.of() : new ArrayList<>(b);
    }

    private static String frozen(Args a, CommandContext ctx) {
        boolean confirm = a.confirmed();
        List<IsoAnimal> frozen = Quarantine.frozen();
        if (frozen.isEmpty()) return "No frozen animals.";
        String action = a.more() ? a.lower() : "list";
        Map<IsoAnimal, Found> places = new IdentityHashMap<>();
        for (Found f : AnimalOps.all(null)) places.put(f.animal(), f);
        switch (action) {
            case "release" -> {
                if (!confirm) return "Dry run: would release " + frozen.size() + " frozen animals. Add 'confirm'.";
                ctx.requireMainThread();
                frozen.forEach(Quarantine::release);
                ctx.audit("frozen release: " + frozen.size());
                return "Released " + frozen.size() + " animals; they will be updated again.";
            }
            case "kill" -> {
                if (!confirm) return "Dry run: would remove " + frozen.size() + " frozen animals. Add 'confirm'.";
                ctx.requireMainThread();
                int n = 0;
                for (IsoAnimal an : frozen) {
                    Found f = places.getOrDefault(an, new Found(an, Place.WORLD, null, (int) an.getX(), (int) an.getY(), (int) an.getZ()));
                    ctx.audit("frozen kill: " + AnimalOps.describe(an));
                    if (AnimalOps.kill(f) == null) n++;
                    Quarantine.release(an);
                }
                return "Removed " + n + " frozen animals.";
            }
            default -> {
                StringBuilder b = new StringBuilder(frozen.size() + " frozen animals:");
                for (int i = 0; i < frozen.size() && i < LIST_LIMIT; i++) {
                    IsoAnimal an = frozen.get(i);
                    Found f = places.get(an);
                    b.append('\n').append(f != null ? line(f) : AnimalOps.describe(an));
                }
                return b.toString();
            }
        }
    }
}
