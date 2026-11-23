package splint.animals;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.ThreadLocalRandom;
import splint.core.Log;

/**
 * Unique animal IDs.
 *
 * <p>Vanilla gives every new animal {@code Rand.Next(10000)}: a random number from 0 to 9999
 * with no check, so a farm of a few hundred animals almost certainly holds duplicates
 * (expected pairs = n(n-1)/2 / 10000; 460 animals give about 10.6). Several systems match
 * animals by this ID, including the duplicate check that deletes animals during growth.
 *
 * <p>Splint replaces it with a per-world counter that only increases and starts above the
 * vanilla range, so a new ID can never equal any vanilla ID or any ID Splint handed out
 * before. The counter is persisted in the save ({@code splint/animal-ids.txt}) in blocks:
 * the file always records a value beyond every ID already issued, so a crash or restart
 * can only skip numbers, never reuse them. Every ID loaded from the save is also observed,
 * and the counter moves past it, so a restored or edited save cannot make it go backwards.
 */
public final class AnimalIds {
    /** Vanilla IDs are 0..9999; Splint never issues those. */
    public static final int FIRST = 10_000;
    private static final int BLOCK = 1_000;
    /** IDs above this are not followed by the counter (leaves room for 1e9 more IDs). */
    private static final int OBSERVE_LIMIT = 2_000_000_000;

    private static boolean loaded;
    private static int next;
    private static int reservedUntil;
    private static Path file;
    /** Test hook: the save folder to use instead of the game's current save. */
    private static Path saveDirOverride;

    private AnimalIds() {}

    public static synchronized int allocate() {
        ensureLoaded();
        if (next >= reservedUntil) reserve();
        int id = next++;
        if (next < FIRST) {               // int overflow after 2^31 IDs: start over above vanilla
            Log.warn("animal ID counter wrapped; restarting at " + FIRST);
            next = FIRST;
            reserve();
        }
        Stats.idsAssigned.incrementAndGet();
        return id;
    }

    /** Records an ID that exists in the world, so the counter never issues it again. */
    public static synchronized void observe(int id) {
        ensureLoaded();
        if (id > OBSERVE_LIMIT) {
            // Only an edited save can hold this; following it would exhaust the counter.
            Log.problem("id-out-of-range", "animal with id " + id + " is outside Splint's range; not moving the counter", null);
            return;
        }
        if (id >= next) {
            next = id + 1;
            if (next >= reservedUntil) reserve();
        }
    }

    public static synchronized String status() {
        ensureLoaded();
        return "nextAnimalId=" + next + " reservedUntil=" + reservedUntil + " file=" + file;
    }

    private static void ensureLoaded() {
        if (loaded) return;
        loaded = true;
        next = FIRST;
        try {
            file = saveDirOverride != null ? saveDirOverride.resolve("splint/animal-ids.txt")
                    : Path.of(zombie.ZomboidFileSystem.instance.getFileNameInCurrentSave("splint", "animal-ids.txt"));
            Integer stored = Files.exists(file) ? readNext(file) : null;
            if (stored != null) {
                next = Math.max(FIRST, stored);
                Log.info("animal ID counter loaded: next=" + next);
            } else if (Files.exists(file) || worldHasAnimals()) {
                // An existing world without a readable counter (Splint newly installed, or the
                // file was lost or truncated). IDs >= 10000 can only exist if Splint issued
                // them before, so start far away from anything it could have issued.
                next = 1_000_000_000 + ThreadLocalRandom.current().nextInt(100_000_000);
                Log.warn("no animal ID counter in this save; starting new IDs at " + next);
            }
        } catch (Throwable t) {
            next = 1_000_000_000 + ThreadLocalRandom.current().nextInt(100_000_000);
            Log.error("cannot read the animal ID counter; starting new IDs at " + next, t);
        }
        reservedUntil = next;
    }

    private static Integer readNext(Path f) {
        try {
            for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                if (line.startsWith("next=")) return Integer.parseInt(line.substring(5).trim());
            }
        } catch (IOException | NumberFormatException e) {
            Log.error("animal ID counter file " + f + " is unreadable", e);
        }
        return null;
    }

    private static boolean worldHasAnimals() {
        try {
            Path apop = saveDirOverride != null ? saveDirOverride.resolve("apop")
                    : Path.of(zombie.ZomboidFileSystem.instance.getFileNameInCurrentSave("apop", "x")).getParent();
            try (var s = Files.list(apop)) {
                return s.findAny().isPresent();
            }
        } catch (Throwable t) {
            return false;
        }
    }

    /** Test hook: forget in-memory state (as a server restart does) and use {@code saveDir}. */
    static synchronized void resetForTest(Path saveDir) {
        saveDirOverride = saveDir;
        loaded = false;
        file = null;
    }

    private static void reserve() {
        reservedUntil = next + BLOCK;
        if (reservedUntil < next) reservedUntil = Integer.MAX_VALUE;
        if (file == null) return;
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            byte[] body = ("# Splint animal ID counter. Every ID below this value may be in use.\nnext="
                    + reservedUntil + "\n").getBytes(StandardCharsets.UTF_8);
            try (FileChannel ch = FileChannel.open(tmp, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING)) {
                ch.write(ByteBuffer.wrap(body));
                ch.force(true);
            }
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Log.problem("id-counter-write", "cannot write " + file, e);
        }
    }
}
