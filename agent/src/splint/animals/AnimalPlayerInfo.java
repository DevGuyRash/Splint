package splint.animals;

import java.util.EnumMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import splint.animals.AnimalOps.Found;
import splint.animals.AnimalOps.Place;
import zombie.characters.IsoPlayer;

/** For /splint players: loaded animals within 30 squares of each player, by place, from one scan. */
final class AnimalPlayerInfo {
    private static final int RADIUS = 30;

    private AnimalPlayerInfo() {}

    static Map<IsoPlayer, String> near(List<IsoPlayer> players) {
        List<Found> animals = AnimalOps.all(null);
        Map<IsoPlayer, String> out = new IdentityHashMap<>();
        for (IsoPlayer p : players) {
            int px = (int) p.getX(), py = (int) p.getY();
            Map<Place, Integer> near = new EnumMap<>(Place.class);
            for (Found f : animals) {
                if (Math.abs(f.x() - px) <= RADIUS && Math.abs(f.y() - py) <= RADIUS) near.merge(f.place(), 1, Integer::sum);
            }
            out.put(p, near.isEmpty() ? "none within " + RADIUS : near + " within " + RADIUS);
        }
        return out;
    }
}
