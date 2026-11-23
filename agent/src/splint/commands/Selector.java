package splint.commands;

import java.util.ArrayList;
import java.util.Arrays;
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
import splint.animals.AnimalOps;
import splint.animals.AnimalOps.Area;
import splint.animals.AnimalOps.Found;
import splint.animals.AnimalOps.Place;
import splint.animals.Quarantine;
import zombie.characters.IsoPlayer;
import zombie.characters.animals.IsoAnimal;
import zombie.vehicles.BaseVehicle;

/**
 * Which animals a command acts on. Every term narrows the selection, so terms combine freely
 * in any order, for example
 * {@code cell 42 36 type chicken stage baby except hutch} or
 * {@code player Bob 40 type cow,sheep sex female age 0-60 limit 20}.
 */
final class Selector {
    static final String HELP = String.join("\n",
            "Where (any number; animals matching any of them):",
            "  cell CX CY | near X Y R | me R | player NAME [R] | hutch X Y Z | trailer VEHICLE_ID | inventory NAME",
            "  (no where-term = everything the server has loaded)",
            "Filters (all must match):",
            "  type T[,T...]       animal type (hen, chick, cow...) or group (chicken, cow, pig...)",
            "  stage baby|adult    baby = will still grow (chick, piglet, lamb...)",
            "  sex male|female     age MIN-MAX (days; MIN- or -MAX allowed)     id N[,N...]",
            "  in PLACES | except PLACES   PLACES = world,hutch,trailer,inventory",
            "  ghosts | frozen | dupes      only uninitialised / frozen / duplicate-ID animals",
            "  limit N             act on at most N animals",
            "Map cell = floor(x/256), floor(y/256), the same numbers as apop_CX_CY.bin.");

    /** Places a destructive command touches unless told otherwise: not players' pockets. */
    static final Set<Place> KILL_DEFAULT_PLACES = EnumSet.of(Place.WORLD, Place.HUTCH, Place.TRAILER);
    private static final int PLAYER_DEFAULT_RADIUS = 50;
    private static final int MAX_RADIUS = 300;

    private final List<Area> areas = new ArrayList<>();
    private final List<Predicate<Found>> wheres = new ArrayList<>();
    private final List<Predicate<Found>> filters = new ArrayList<>();
    private final List<String> labels = new ArrayList<>();
    private Set<Place> places;
    private boolean onlyDupes;
    private boolean ghostScan;
    int limit = Integer.MAX_VALUE;

    private Selector() {}

    static Selector parse(List<String> terms, String executor, boolean defaultSafePlaces) {
        Selector s = new Selector();
        // Destructive commands start from the safe places; 'in' replaces, 'except' subtracts,
        // 'inventory NAME' adds the named player's pockets.
        if (defaultSafePlaces) s.places = EnumSet.copyOf(KILL_DEFAULT_PLACES);
        Cursor c = new Cursor(terms);
        while (c.more()) {
            String term = c.word().toLowerCase(Locale.ROOT);
            switch (term) {
                case "cell" -> {
                    int cx = c.integer("CX"), cy = c.integer("CY");
                    Area a = Area.cell(cx, cy);
                    s.area(a, "cell " + cx + "," + cy);
                }
                case "near" -> {
                    int x = c.integer("X"), y = c.integer("Y"), r = Math.min(c.integer("R"), MAX_RADIUS);
                    s.area(Area.radius(x, y, r), "within " + r + " of " + x + "," + y);
                }
                case "me" -> {
                    IsoPlayer p = player(executor);
                    int r = Math.min(c.integer("R"), MAX_RADIUS);
                    s.area(Area.radius((int) p.getX(), (int) p.getY(), r), "within " + r + " of " + executor);
                }
                case "player" -> {
                    String name = c.word();
                    IsoPlayer p = player(name);
                    int r = c.nextIsInt() ? Math.min(c.integer("R"), MAX_RADIUS) : PLAYER_DEFAULT_RADIUS;
                    s.area(Area.radius((int) p.getX(), (int) p.getY(), r), "within " + r + " of " + p.getUsername());
                }
                case "hutch" -> {
                    int x = c.integer("X"), y = c.integer("Y"), z = c.integer("Z");
                    s.where(f -> f.place() == Place.HUTCH && f.x() == x && f.y() == y && f.z() == z, "hutch " + x + "," + y + "," + z);
                }
                case "trailer" -> {
                    int id = c.integer("VEHICLE_ID");
                    s.where(f -> f.place() == Place.TRAILER && ((BaseVehicle) f.holder()).getId() == id, "trailer #" + id);
                }
                case "inventory" -> {
                    String name = c.word();
                    s.where(f -> f.place() == Place.INVENTORY && carrier(f).equalsIgnoreCase(name), "carried by " + name);
                    s.allowPlace(Place.INVENTORY);
                }
                case "type" -> {
                    List<String> types = c.list();
                    Predicate<Found> any = types.stream().map(AnimalOps::ofType).reduce(f -> false, Predicate::or);
                    s.filter(any, "type " + String.join(",", types));
                }
                case "stage" -> {
                    String st = c.word().toLowerCase(Locale.ROOT);
                    boolean baby = switch (st) {
                        case "baby", "babies", "young", "juvenile" -> true;
                        case "adult", "adults", "grown" -> false;
                        default -> throw new SplintCommand.Usage("stage must be baby or adult (use type for exact stages like chick or hen)");
                    };
                    s.filter(f -> !AnimalOps.isGhost(f.animal()) && AnimalOps.isJuvenile(f.animal()) == baby, "stage " + st);
                }
                case "sex" -> {
                    String sx = c.word().toLowerCase(Locale.ROOT);
                    boolean female = switch (sx) {
                        case "female", "f" -> true;
                        case "male", "m" -> false;
                        default -> throw new SplintCommand.Usage("sex must be male or female");
                    };
                    s.filter(f -> !AnimalOps.isGhost(f.animal()) && f.animal().isFemale() == female, "sex " + sx);
                }
                case "age" -> {
                    String range = c.word();
                    int dash = range.indexOf('-');
                    if (dash < 0) throw new SplintCommand.Usage("age takes MIN-MAX days, e.g. age 0-30, age 120-, age -7");
                    int min = dash == 0 ? Integer.MIN_VALUE : parse(range.substring(0, dash), "age");
                    int max = dash == range.length() - 1 ? Integer.MAX_VALUE : parse(range.substring(dash + 1), "age");
                    s.filter(f -> f.animal().getData() != null && f.animal().getData().getAge() >= min
                            && f.animal().getData().getAge() <= max, "age " + range);
                }
                case "id" -> {
                    Set<Integer> ids = c.list().stream().map(v -> parse(v, "id")).collect(Collectors.toSet());
                    s.where(f -> ids.contains(f.animal().getAnimalID()), "id " + ids);
                }
                case "in" -> s.setPlaces(places(c.list()), true);
                case "except" -> s.setPlaces(places(c.list()), false);
                case "ghosts", "ghost" -> {
                    s.filter(f -> AnimalOps.isGhost(f.animal()), "ghosts");
                    s.ghostScan = true;
                }
                case "frozen" -> s.filter(f -> Quarantine.isFrozen(f.animal()), "frozen");
                case "dupes", "duplicates" -> {
                    s.onlyDupes = true;
                    s.labels.add("duplicate IDs");
                }
                case "limit" -> s.limit = Math.max(1, c.integer("N"));
                case "all" -> s.labels.add("all");
                default -> throw new SplintCommand.Usage("Unknown term '" + term + "'.\n" + HELP);
            }
        }
        return s;
    }

    private void allowPlace(Place p) {
        if (places != null) places.add(p);
    }

    private void area(Area a, String label) {
        areas.add(a);
        wheres.add(f -> a.contains(f.x(), f.y(), f.z()));
        labels.add(label);
    }

    private void where(Predicate<Found> p, String label) {
        wheres.add(p);
        labels.add(label);
    }

    private void filter(Predicate<Found> p, String label) {
        filters.add(p);
        labels.add(label);
    }

    private void setPlaces(Set<Place> given, boolean include) {
        Set<Place> base = places != null ? places : EnumSet.allOf(Place.class);
        if (include) places = EnumSet.copyOf(given);
        else {
            places = EnumSet.copyOf(base);
            places.removeAll(given);
        }
    }

    String label() {
        String what = labels.isEmpty() ? "all loaded animals" : String.join(", ", labels);
        if (places == null || places.equals(EnumSet.allOf(Place.class))) return what;
        return what + " [places: " + places.stream().map(p -> p.name().toLowerCase(Locale.ROOT))
                .collect(Collectors.joining(",")) + "]";
    }

    /** For /splint players: whether a map point lies in one of the selection's areas. */
    boolean coversPoint(int x, int y, int z) {
        return areas.isEmpty() || areas.stream().anyMatch(a -> a.contains(x, y, z));
    }

    /** Everything that matches, nearest first by map position. */
    List<Found> run() {
        Map<IsoAnimal, Found> pool = new IdentityHashMap<>();
        if (areas.isEmpty()) {
            for (Found f : AnimalOps.all(null)) pool.put(f.animal(), f);
            if (ghostScan) {
                // Square-only ghosts are not in any list; scan around every player.
                for (IsoPlayer p : AnimalOps.players()) {
                    for (Found f : AnimalOps.all(Area.radius((int) p.getX(), (int) p.getY(), 60))) pool.putIfAbsent(f.animal(), f);
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

    // --- helpers --------------------------------------------------------------------------

    static String carrier(Found f) {
        for (IsoPlayer p : AnimalOps.players()) {
            try {
                if (p.getInventory().getAllTypeRecurse("Animal").contains(f.holder())) return p.getUsername();
            } catch (Throwable ignored) {
                // inventory not readable; skip this player
            }
        }
        return "";
    }

    static IsoPlayer player(String name) {
        if (name == null) throw new SplintCommand.Usage("'me' needs an in-game admin; use 'near X Y R' or 'player NAME' from the console.");
        for (IsoPlayer p : AnimalOps.players()) if (p.getUsername().equalsIgnoreCase(name)) return p;
        throw new SplintCommand.Usage("No online player named '" + name + "'.");
    }

    private static Set<Place> places(List<String> names) {
        Set<Place> out = EnumSet.noneOf(Place.class);
        for (String n : names) {
            switch (n.toLowerCase(Locale.ROOT)) {
                case "world", "map", "ground" -> out.add(Place.WORLD);
                case "hutch", "hutches" -> out.add(Place.HUTCH);
                case "trailer", "trailers" -> out.add(Place.TRAILER);
                case "inventory", "inventories", "carried" -> out.add(Place.INVENTORY);
                default -> throw new SplintCommand.Usage("Unknown place '" + n + "' (world, hutch, trailer, inventory)");
            }
        }
        return out;
    }

    static int parse(String s, String what) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            throw new SplintCommand.Usage("Not a number for " + what + ": '" + s + "'");
        }
    }

    private static final class Cursor {
        private final List<String> terms;
        private int i;

        Cursor(List<String> terms) { this.terms = terms; }

        boolean more() { return i < terms.size(); }

        String word() {
            if (!more()) throw new SplintCommand.Usage("Missing value after '" + terms.get(i - 1) + "'.");
            return terms.get(i++);
        }

        int integer(String what) { return parse(word(), what); }

        boolean nextIsInt() {
            if (!more()) return false;
            try {
                Integer.parseInt(terms.get(i));
                return true;
            } catch (NumberFormatException e) {
                return false;
            }
        }

        /** A comma list, also accepting spaces after commas: "cow, sheep". */
        List<String> list() {
            List<String> out = new ArrayList<>();
            String raw = word();
            out.addAll(Arrays.asList(raw.split(",")));
            while (raw.endsWith(",") && more()) {
                raw = word();
                out.addAll(Arrays.asList(raw.split(",")));
            }
            out.removeIf(String::isBlank);
            return out;
        }
    }
}
