package splint.animals;

import java.util.List;
import java.util.Map;
import splint.core.command.ModuleCommand;
import splint.core.module.SplintModule;
import splint.core.patch.Patcher.Patch;
import splint.core.patch.Patcher.Redirect;
import zombie.characters.IsoPlayer;

/**
 * Animals: unique IDs generated at the source and indexed for O(1) duplicate checks, no
 * half-built ("ghost") animals, per-animal failure isolation, removal that always completes,
 * and {@code /splint animals}. See docs/investigation.md for the failures behind each patch.
 */
public final class AnimalsModule implements SplintModule {
    static final String ID = "animals";

    private static final String ANIMAL = "zombie/characters/animals/IsoAnimal";
    private static final String HUTCH = "zombie/iso/objects/IsoHutch";
    private static final String POPULATION = "zombie/characters/animals/AnimalPopulationManager";
    private static final String MANAGER = "zombie/characters/animals/AnimalManagerMain";
    private static final String HOOKS = "splint/animals/AnimalHooks";

    private static final List<Patch> PATCHES = List.of(
            // Identity: restored IDs observed; an animal's ID is claimed in the index when it enters
            // the world (never while deserialising, when the previous holder may still exist).
            new Patch(ANIMAL, "load", "(Ljava/nio/ByteBuffer;IZ)V", HOOKS, "load"),
            new Patch(ANIMAL, "addToWorld", "()V", HOOKS, "addToWorld"),
            new Patch(ANIMAL, "setAnimalID", "(I)V", HOOKS, "setAnimalID"),
            new Patch(ANIMAL, "copyFrom", "(Lzombie/characters/animals/IsoAnimal;)V", HOOKS, "copyFrom"),
            new Patch("zombie/characters/animals/datas/AnimalData", "grow", "(Ljava/lang/String;)V", HOOKS, "grow"),
            // Construction: the checks that leave half-built animals in the world.
            new Patch(ANIMAL, "checkForChickenpocalypse", "(Lzombie/characters/animals/IsoAnimal;)Z", HOOKS, "checkForChickenpocalypse"),
            new Patch(ANIMAL, "checkForWater", "()Z", HOOKS, "checkForWater"),
            // Per-animal isolation: one broken animal must not stop the world update.
            new Patch(ANIMAL, "update", "()V", HOOKS, "update"),
            new Patch(ANIMAL, "updateLoopingSounds", "()V", HOOKS, "updateLoopingSounds"),
            new Patch(HUTCH, "update", "()V", HOOKS, "hutchUpdate"),
            // Removal that always finishes, even for animals whose definition is missing.
            new Patch(ANIMAL, "removeFromWorld", "()V", HOOKS, "removeFromWorld"),
            new Patch(ANIMAL, "delete", "()V", HOOKS, "delete"),
            new Patch(ANIMAL, "unloaded", "()V", HOOKS, "unloaded"),
            // Population bookkeeping that serialises or virtualises every animal it is handed.
            new Patch(POPULATION, "removeChunkFromWorld", "(Lzombie/iso/IsoChunk;)V", HOOKS, "removeChunkFromWorld", true),
            new Patch(POPULATION, "virtualizeAnimal", "(Lzombie/characters/animals/IsoAnimal;)V", HOOKS, "virtualizeAnimal", true),
            new Patch(MANAGER, "saveRealAnimals", "()V", HOOKS, "saveRealAnimals", true),
            new Patch("zombie/characters/animals/VirtualAnimal", "save", "(Ljava/nio/ByteBuffer;)V", HOOKS, "herdSave", true));

    /**
     * Vanilla's only source of new animal IDs is {@code this.animalId = Rand.Next(10000)} in
     * IsoAnimal.init. That expression now calls {@link AnimalIds#next(zombie.characters.animals.IsoAnimal, int)}:
     * on the server (and single player) no random ID is ever generated and the new animal is
     * indexed under its ID at once; a multiplayer client keeps vanilla's value, which the
     * server's own ID overwrites.
     */
    private static final List<Redirect> REDIRECTS = List.of(
            new Redirect(ANIMAL, "init", "(Lzombie/characters/animals/datas/AnimalBreed;)V",
                    "zombie/core/random/Rand", "Next", "(I)I", "animalId", "splint/animals/AnimalIds", "next", true));


    /** Created on first use, never in premain (see SplintModule). */
    private static final class Commands {
        static final List<ModuleCommand> ALL = List.of(new AnimalCommands());
    }

    @Override
    public void start() {
        AnimalDescriber.register();
    }

    @Override
    public String id() { return ID; }

    @Override
    public String description() {
        return "unique animal IDs with O(1) duplicate checks, no half-built animals, failure isolation, /splint animals";
    }

    @Override
    public List<Patch> patches() { return PATCHES; }

    @Override
    public List<Redirect> redirects() { return REDIRECTS; }

    @Override
    public List<ModuleCommand> commands() { return Commands.ALL; }

    @Override
    public String status() {
        return "animals: " + AnimalIds.status() + " indexedIds=" + AnimalIndex.size() + " frozen=" + AnimalCommands.frozenAnimals().size();
    }

    @Override
    public Map<IsoPlayer, String> aboutPlayers(List<IsoPlayer> players) {
        return AnimalPlayerInfo.near(players);
    }
}
