package splint.animals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

/**
 * Persistence rules of the animal ID counter that the lab cannot exercise in one session:
 * restarts, crashes between reservations, loaded IDs, and installing into an existing world.
 * Expected values follow from the rules stated in AnimalIds' documentation.
 */
public final class AnimalIdsTest {
    private static int failures;

    public static void main(String[] args) throws Exception {
        freshWorldStartsAboveVanillaRange();
        restartNeverReusesAnId();
        crashLosesOnlyUnusedReservation();
        loadedIdsMoveTheCounter();
        existingWorldWithoutCounterStartsFarAway();
        truncatedCounterFileIsNotTrustedAsFresh();
        if (failures > 0) {
            System.out.println(failures + " AnimalIds check(s) FAILED");
            System.exit(1);
        }
        System.out.println("AnimalIds: all checks passed");
    }

    private static Path world() throws Exception {
        return Files.createTempDirectory("splint-ids");
    }

    static void freshWorldStartsAboveVanillaRange() throws Exception {
        AnimalIds.resetForTest(world());
        check("first id of a fresh world", AnimalIds.allocate() == 10_000);
        check("second id", AnimalIds.allocate() == 10_001);
    }

    static void restartNeverReusesAnId() throws Exception {
        Path w = world();
        AnimalIds.resetForTest(w);
        Set<Integer> issued = new HashSet<>();
        for (int i = 0; i < 2_500; i++) check("unique within a run", issued.add(AnimalIds.allocate()));
        int max = issued.stream().max(Integer::compare).orElseThrow();
        AnimalIds.resetForTest(w);                       // server restart, same save
        int after = AnimalIds.allocate();
        check("first id after restart (" + after + ") is above every id issued before (" + max + ")", after > max);
    }

    static void crashLosesOnlyUnusedReservation() throws Exception {
        Path w = world();
        AnimalIds.resetForTest(w);
        AnimalIds.allocate();                            // reserves 10000..10999 on disk
        String onDisk = Files.readString(w.resolve("splint/animal-ids.txt"));
        check("counter file records the reservation end: " + onDisk.strip(), onDisk.contains("next=11000"));
        AnimalIds.resetForTest(w);                       // crash: no shutdown hook ran
        check("restart after crash continues at the reservation end", AnimalIds.allocate() == 11_000);
    }

    static void loadedIdsMoveTheCounter() throws Exception {
        AnimalIds.resetForTest(world());
        AnimalIds.observe(5);                            // a vanilla id: below the counter, ignored
        check("vanilla id does not move the counter", AnimalIds.allocate() == 10_000);
        AnimalIds.observe(250_000);                      // e.g. a restored backup with higher ids
        check("counter moves past a loaded id", AnimalIds.allocate() == 250_001);
    }

    static void existingWorldWithoutCounterStartsFarAway() throws Exception {
        Path w = world();
        Files.createDirectories(w.resolve("apop"));
        Files.writeString(w.resolve("apop/apop_42_36.bin"), "x");
        AnimalIds.resetForTest(w);
        int id = AnimalIds.allocate();
        check("existing world without counter starts at >= 1e9 (" + id + ")", id >= 1_000_000_000);
    }

    static void truncatedCounterFileIsNotTrustedAsFresh() throws Exception {
        Path w = world();
        Files.createDirectories(w.resolve("splint"));
        Files.writeString(w.resolve("splint/animal-ids.txt"), "");   // e.g. cut short by a power loss
        AnimalIds.resetForTest(w);
        int id = AnimalIds.allocate();
        check("unreadable counter file starts at >= 1e9, not at 10000 (" + id + ")", id >= 1_000_000_000);
    }

    private static void check(String what, boolean ok) {
        if (!ok) {
            failures++;
            System.out.println("FAIL: " + what);
        }
    }
}
