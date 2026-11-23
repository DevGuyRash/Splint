package splint.agent;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import splint.agent.Patcher.Patch;
import splint.core.Log;

/**
 * Java agent entry point. Add {@code -javaagent:java/splint.jar} to the server's JVM options
 * (the {@code vmArgs} list of ProjectZomboid64.json, or the java command of start-server.sh).
 *
 * <p>Nothing here edits files on disk: the game's classes are rewritten in memory as the JVM
 * loads them, and only the methods listed in {@link #PATCHES} change.
 */
public final class SplintAgent {
    public static final String VERSION = "0.1.0";

    private static final String ANIMAL = "zombie/characters/animals/IsoAnimal";
    private static final String POPULATION = "zombie/characters/animals/AnimalPopulationManager";
    private static final String MANAGER = "zombie/characters/animals/AnimalManagerMain";
    private static final String HUTCH = "zombie/iso/objects/IsoHutch";
    private static final String COMMAND_BASE = "zombie/commands/CommandBase";
    private static final String ANIMAL_HOOKS = "splint/animals/AnimalHooks";
    private static final String COMMAND_HOOKS = "splint/commands/CommandHooks";

    static final List<Patch> PATCHES = List.of(
            // Identity: IDs restored from a save are recorded so the allocator never issues them.
            new Patch(ANIMAL, "load", "(Ljava/nio/ByteBuffer;IZ)V", ANIMAL_HOOKS, "load"),
            // Construction: the checks that leave half-built animals in the world.
            new Patch(ANIMAL, "checkForChickenpocalypse", "(Lzombie/characters/animals/IsoAnimal;)Z", ANIMAL_HOOKS, "checkForChickenpocalypse"),
            new Patch(ANIMAL, "checkForWater", "()Z", ANIMAL_HOOKS, "checkForWater"),
            // Per-animal isolation: one broken animal must not stop the world update.
            new Patch(ANIMAL, "update", "()V", ANIMAL_HOOKS, "update"),
            new Patch(ANIMAL, "updateLoopingSounds", "()V", ANIMAL_HOOKS, "updateLoopingSounds"),
            // Removal that always finishes, even for animals whose definition is missing.
            new Patch(ANIMAL, "removeFromWorld", "()V", ANIMAL_HOOKS, "removeFromWorld"),
            new Patch(ANIMAL, "delete", "()V", ANIMAL_HOOKS, "delete"),
            new Patch(ANIMAL, "unloaded", "()V", ANIMAL_HOOKS, "unloaded"),
            new Patch(HUTCH, "update", "()V", ANIMAL_HOOKS, "hutchUpdate"),
            // Population bookkeeping that serialises or virtualises every animal it is handed.
            new Patch(POPULATION, "removeChunkFromWorld", "(Lzombie/iso/IsoChunk;)V", ANIMAL_HOOKS, "removeChunkFromWorld", true),
            new Patch(POPULATION, "virtualizeAnimal", "(Lzombie/characters/animals/IsoAnimal;)V", ANIMAL_HOOKS, "virtualizeAnimal", true),
            new Patch(MANAGER, "saveRealAnimals", "()V", ANIMAL_HOOKS, "saveRealAnimals", true),
            // Admin commands: /splint ... from the console, RCON and in-game chat.
            new Patch(COMMAND_BASE, "findCommandCls", "(Ljava/lang/String;)Ljava/lang/Class;", COMMAND_HOOKS, "findCommandCls"),
            new Patch(COMMAND_BASE, "getSubClasses", "()[Ljava/lang/Class;", COMMAND_HOOKS, "getSubClasses"));

    /**
     * Call sites replaced in place. Vanilla's only source of new animal IDs is
     * {@code this.animalId = Rand.Next(10000)} in IsoAnimal.init; that call now goes to the
     * unique allocator, so no random ID is ever generated.
     */
    static final List<Patcher.Redirect> REDIRECTS = List.of(
            new Patcher.Redirect(ANIMAL, "init", "(Lzombie/characters/animals/datas/AnimalBreed;)V",
                    "zombie/core/random/Rand", "Next", "(I)I", "animalId", "splint/animals/AnimalIds", "next"));

    /** Patches actually applied, as {@code owner.nameDescriptor}; reported by /splint status. */
    public static final Set<String> APPLIED = ConcurrentHashMap.newKeySet();
    /** Game classes the transformer has seen load. */
    public static final Set<String> LOADED = ConcurrentHashMap.newKeySet();

    private SplintAgent() {}

    public static void premain(String args, Instrumentation inst) {
        Map<String, List<Patch>> byOwner = PATCHES.stream().collect(Collectors.groupingBy(Patch::owner));
        Map<String, List<Patcher.Redirect>> redirectsByOwner = REDIRECTS.stream()
                .collect(Collectors.groupingBy(Patcher.Redirect::owner));
        inst.addTransformer(new ClassFileTransformer() {
            @Override
            public byte[] transform(Module module, ClassLoader loader, String name, Class<?> redefined,
                                    ProtectionDomain domain, byte[] bytes) {
                if (name == null) return null;
                List<Patch> patches = byOwner.getOrDefault(name, List.of());
                List<Patcher.Redirect> redirects = redirectsByOwner.getOrDefault(name, List.of());
                if (patches.isEmpty() && redirects.isEmpty()) return null;
                LOADED.add(name);
                byte[] out;
                try {
                    out = Patcher.apply(bytes, patches, redirects, APPLIED);
                } catch (Throwable t) {
                    // Never take the server down over a patch: run that class unpatched.
                    Log.boot("could not patch " + name + "; running it unpatched", t);
                    return null;
                }
                // The JVM discards a transformer's result if it throws, so report only through
                // the boot log, which cannot throw or call back into game code.
                Log.boot("patched " + name.replace('/', '.') + " (" + (patches.size() + redirects.size()) + " change(s), loader "
                        + (loader == null ? "bootstrap" : loader.getClass().getSimpleName()) + ")", null);
                return out;
            }
        });
        Log.boot("Splint " + VERSION + " agent loaded (" + total() + " patches registered)", null);
    }

    /**
     * Patches whose class has loaded but whose method was not found, e.g. after a game update
     * changed its signature. Classes that have not loaded yet are not counted.
     */
    public static List<String> missing() {
        List<String> out = new java.util.ArrayList<>(PATCHES.stream().filter(p -> LOADED.contains(p.owner()))
                .map(p -> p.owner() + "." + p.name() + p.descriptor())
                .filter(k -> !APPLIED.contains(k)).toList());
        REDIRECTS.stream().filter(r -> LOADED.contains(r.owner()) && !APPLIED.contains(r.key()))
                .forEach(r -> out.add(r.key()));
        return out;
    }

    public static int total() {
        return PATCHES.size() + REDIRECTS.size();
    }
}
