package splint.core.command;

import splint.core.world.Area;
import splint.core.world.World;
import zombie.characters.IsoPlayer;

/**
 * Location terms every module's selections share:
 * {@code cell CX CY | near X Y R | me R | player NAME [R]}.
 */
public final class AreaTerms {
    public static final String HELP = "cell CX CY | near X Y R | me R | player NAME [R]";
    private static final int MAX_RADIUS = 300;
    private static final int PLAYER_DEFAULT_RADIUS = 50;

    public record Parsed(Area area, String label) {}

    private AreaTerms() {}

    /** Parses one location term whose keyword was just read, or returns null if it is not one. */
    public static Parsed parse(String keyword, Args a, CommandContext ctx) {
        switch (keyword) {
            case "cell" -> {
                int cx = a.integer("CX"), cy = a.integer("CY");
                return new Parsed(Area.cell(cx, cy), "cell " + cx + "," + cy);
            }
            case "near" -> {
                int x = a.integer("X"), y = a.integer("Y"), r = Math.min(a.integer("R"), MAX_RADIUS);
                return new Parsed(Area.radius(x, y, r), "within " + r + " of " + x + "," + y);
            }
            case "me" -> {
                if (ctx.player() == null) throw new Usage("'me' needs an in-game admin; use 'near X Y R' or 'player NAME' from the console.");
                IsoPlayer p = require(ctx.player());
                int r = Math.min(a.integer("R"), MAX_RADIUS);
                return new Parsed(Area.radius((int) p.getX(), (int) p.getY(), r), "within " + r + " of " + p.getUsername());
            }
            case "player" -> {
                IsoPlayer p = require(a.word());
                int r = a.nextIsInt() ? Math.min(a.integer("R"), MAX_RADIUS) : PLAYER_DEFAULT_RADIUS;
                return new Parsed(Area.radius((int) p.getX(), (int) p.getY(), r), "within " + r + " of " + p.getUsername());
            }
            default -> {
                return null;
            }
        }
    }

    private static IsoPlayer require(String name) {
        IsoPlayer p = World.player(name);
        if (p == null) throw new Usage("No online player named '" + name + "'.");
        return p;
    }
}
