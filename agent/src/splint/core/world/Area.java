package splint.core.world;

/** A box of squares, all bounds inclusive. */
public record Area(int x0, int y0, int x1, int y1, int z0, int z1) {
    /** Every floor Build 42 can have. */
    public static final int Z_MIN = -32, Z_MAX = 31;

    public static Area cell(int cx, int cy) {
        return new Area(cx * World.CELL, cy * World.CELL, cx * World.CELL + World.CELL - 1, cy * World.CELL + World.CELL - 1,
                Z_MIN, Z_MAX);
    }

    public static Area radius(int x, int y, int r) {
        return new Area(x - r, y - r, x + r, y + r, Z_MIN, Z_MAX);
    }

    public boolean contains(int x, int y, int z) {
        return x >= x0 && x <= x1 && y >= y0 && y <= y1 && z >= z0 && z <= z1;
    }
}
