package splint.animals;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import splint.animals.AnimalOps.Found;
import splint.animals.AnimalOps.Place;
import splint.core.command.AreaTerms;
import splint.core.command.Args;
import splint.core.command.CommandContext;
import splint.core.command.Usage;
import splint.core.world.Area;
import splint.core.world.World;
import zombie.characters.IsoPlayer;
import zombie.characters.animals.IsoAnimal;
import zombie.vehicles.BaseVehicle;

/**
 * Which animals a command acts on. Location terms come from {@link AreaTerms} (shared by all
 * modules); the rest are animal filters. Every term narrows the selection, so terms combine in
 * any order: {@code cell 42 36 type chicken stage baby except hutch},
 * {@code player Bob 40 type cow,sheep sex female age 0-60 limit 20}.
 */
final class AnimalSelector {
    static final String HELP = String.join("\n",
            "  Where (any number; animals matching any of them):",
            "    " + AreaTerms.HELP + " | hutch X Y Z | trailer VEHICLE_ID | inventory NAME",
            "    (no where-term = everything the server has loaded)",
            "  Filters (all must match):",
            "    type T[,T...]       animal type (hen, chick, cow...) or group (chicken, cow, pig...)",
            "    stage baby|adult    baby = will still grow (chick, piglet, lamb...)",
            "    sex male|female     age MIN-MAX (days; MIN- or -MAX allowed)     id N[,N...]",
            "    in PLACES | except PLACES   PLACES = world,hutch,trailer,inventory",
            "    ghosts | frozen | dupes      only uninitialised / frozen / duplicate-ID animals",
            "    limit N             act on at most N animals");

    /** Places a destructive command touches unless told otherwise: not players' pockets. */
    static final Set<Place> KILL_DEFAULT_PLACES = EnumSet.of(Place.WORLD, Place.HUTCH, Place.TRAILER);
    private static final int GHOST_SCAN_RADIUS = 60;

    private final List<Area> areas = new ArrayList<>();
    private final List<Predicate<Found>> wheres = new ArrayList<>();
    private final List<Predicate<Found>> filters = new ArrayList<>();
    private final List<String> labels = new ArrayList<>();
    private Set<Place> places;
    private boolean onlyDupes;
    private boolean ghostScan;
    private int limit = Integer.MAX_VALUE;

    private AnimalSelector() {}

    /**
     * Reads selection terms until the words run out. {@code destructive} starts from the safe
     * places (world, hutch, trailer); {@code in} replaces them, {@code except} subtracts, and
     * {@code inventory NAME} adds that player's pockets.
     */
    static AnimalSelector parse(Args a, CommandContext ctx, boolean destructive) {
        AnimalSelector s = new AnimalSelector();
        if (destructive) s.places = EnumSet.copyOf(KILL_DEFAULT_PLACES);
        while (a.more()) {
            String term = a.lower();
            AreaTerms.Parsed area = AreaTerms.parse(term, a, ctx);
            if (area != null) {
                s.areas.add(area.area());
                s.wheres.add(f -> area.area().contains(f.x(), f.y(), f.z()));
                s.labels.add(area.label());
                continue;
            }
            switch (term) {
                case "hutch" -> {
                    int x = a.integer("X"), y = a.integer("Y"), z = a.integer("Z");
                    s.where(f -> f.place() == Place.HUTCH && f.x() == x && f.y() == y && f.z() == z, "hutch " + x + "," + y + "," + z);
                }
                case "trailer" -> {
                    int id = a.integer("VEHICLE_ID");
                    s.where(f -> f.place() == Place.TRAILER && ((BaseVehicle) f.holder()).getId() == id, "trailer #" + id);
                }
                case "inventory" -> {
                    String name = a.word();
                    s.where(f -> f.place() == Place.INVENTORY && carrier(f).equalsIgnoreCase(name), "carried by " + name);
                    if (s.places != null) s.places.add(Place.INVENTORY);
                }
                case "type" -> {
                    List<String> types = a.list();
                    s.filter(types.stream().map(AnimalOps::ofType).reduce(f -> false, Predicate::or), "type " + String.join(",", types));
                }
                case "stage" -> {
                    String st = a.lower();
                    boolean baby = switch (st) {
                        case "baby", "babies", "young", "juvenile" -> true;
                        case "adult", "adults", "grown" -> false;
                        default -> throw new Usage("stage must be baby or adult (use type for exact stages like chick or hen)");
                    };
                    s.filter(f -> !AnimalOps.isGhost(f.animal()) && AnimalOps.isJuvenile(f.animal()) == baby, "stage " + st);
                }
                case "sex" -> {
                    String sx = a.lower();
                    boolean female = switch (sx) {
                        case "female", "f" -> true;
                        case "male", "m" -> false;
                        default -> throw new Usage("sex must be male or female");
                    };
                    s.filter(f -> !AnimalOps.isGhost(f.animal()) && f.animal().isFemale() == female, "sex " + sx);
                }
                case "age" -> {
                    String range = a.word();
                    int dash = range.indexOf('-');
                    if (dash < 0) throw new Usage("age takes MIN-MAX days, e.g. age 0-30, age 120-, age -7");
                    int min = dash == 0 ? Integer.MIN_VALUE : Args.number(range.substring(0, dash), "age");
                    int max = dash == range.length() - 1 ? Integer.MAX_VALUE : Args.number(range.substring(dash + 1), "age");
                    s.filter(f -> f.animal().getData() != null && f.animal().getData().getAge() >= min
                            && f.animal().getData().getAge() <= max, "age " + range);
                }
                case "id" -> {
                    Set<Integer> ids = a.list().stream().map(v -> Args.number(v, "id")).collect(Collectors.toSet());
                    s.where(f -> ids.contains(f.animal().getAnimalID()), "id " + ids);
                }
                case "in" -> s.places = places(a.list());
                case "except" -> {
                    Set<Place> base = s.places != null ? s.places : EnumSet.allOf(Place.class);
                    base.removeAll(places(a.list()));
                    s.places = base;
                }
                case "ghosts", "ghost" -> {
                    s.filter(f -> AnimalOps.isGhost(f.animal()), "ghosts");
                    s.ghostScan = true;
                }
                case "frozen" -> s.filter(f -> Quarantine.isFrozen(f.animal()), "frozen");
                case "dupes", "duplicates" -> {
                    s.onlyDupes = true;
                    s.labels.add("duplicate IDs");
                }
                case "limit" -> s.limit = Math.max(1, a.integer("N"));
                case "all" -> s.labels.add("all");
                default -> throw new Usage("Unknown term '" + term + "'. Selection terms:\n" + HELP);
            }
        }
        return s;
    }

    private void where(Predicate<Found> p, String label) {
        wheres.add(p);
        labels.add(label);
    }

    private void filter(Predicate<Found> p, String label) {
        filters.add(p);
        labels.add(label);
    }

    String label() {
        String what = labels.isEmpty() ? "all loaded animals" : String.join(", ", labels);
        if (places == null || places.equals(EnumSet.allOf(Place.class))) return what;
        return what + " [places: " + places.stream().map(p -> p.name().toLowerCase(Locale.ROOT)).collect(Collectors.joining(",")) + "]";
    }

    /** Everything that matches, ordered by map position. */
    List<Found> run() {
        Map<IsoAnimal, Found> pool = new IdentityHashMap<>();
        if (areas.isEmpty()) {
            for (Found f : AnimalOps.all(null)) pool.put(f.animal(), f);
            if (ghostScan) {
                // Square-only ghosts are in no list; scan the squares around every player.
                for (IsoPlayer p : World.players()) {
                    Area around = Area.radius((int) p.getX(), (int) p.getY(), GHOST_SCAN_RADIUS);
                    for (Found f : AnimalOps.all(around)) pool.putIfAbsent(f.animal(), f);
                }
            }
        } else {
            for (Area a : areas) for (Found f : AnimalOps.all(a)) pool.putIfAbsent(f.animal(), f);
        }
        List<Found> out = new ArrayList<>();
        for (Found f : pool.values()) {
            if (places != null && !places.contains(f.place())) continue;
            if (!wheres.isEmpty() && wheres.stream().noneMatch(w -> w.test(f))) continue;
            if (!filters.stream().allMatch(p -> p.test(f))) continue;
            out.add(f);
        }
        if (onlyDupes) {
            Map<Integer, Integer> counts = new HashMap<>();
            for (Found f : AnimalOps.all(null)) {
                if (!AnimalOps.isGhost(f.animal())) counts.merge(f.animal().getAnimalID(), 1, Integer::sum);
            }
            out.removeIf(f -> counts.getOrDefault(f.animal().getAnimalID(), 0) < 2);
        }
        out.sort(Comparator.comparingInt(Found::x).thenComparingInt(Found::y));
        return out.size() > limit ? new ArrayList<>(out.subList(0, limit)) : out;
    }

    static String carrier(Found f) {
        for (IsoPlayer p : World.players()) {
            try {
                if (p.getInventory().getAllTypeRecurse("Animal").contains(f.holder())) return p.getUsername();
            } catch (Throwable ignored) {
                // inventory not readable; skip this player
            }
        }
        return "";
    }

    private static Set<Place> places(List<String> names) {
        Set<Place> out = EnumSet.noneOf(Place.class);
        for (String n : names) {
            switch (n.toLowerCase(Locale.ROOT)) {
                case "world", "map", "ground" -> out.add(Place.WORLD);
                case "hutch", "hutches" -> out.add(Place.HUTCH);
                case "trailer", "trailers" -> out.add(Place.TRAILER);
                case "inventory", "inventories", "carried" -> out.add(Place.INVENTORY);
                default -> throw new Usage("Unknown place '" + n + "' (world, hutch, trailer, inventory)");
            }
        }
        return out;
    }
}
