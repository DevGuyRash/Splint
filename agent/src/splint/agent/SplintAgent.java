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
            // Identity: unique IDs for new animals, loaded IDs observed and de-duplicated.
            new Patch(ANIMAL, "init", "(Lzombie/characters/animals/datas/AnimalBreed;)V", ANIMAL_HOOKS, "init"),
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

    /** Patches actually applied, as {@code owner.nameDescriptor}; reported by /splint status. */
    public static final Set<String> APPLIED = ConcurrentHashMap.newKeySet();
    /** Game classes the transformer has seen load. */
    public static final Set<String> LOADED = ConcurrentHashMap.newKeySet();

    private SplintAgent() {}

    public static void premain(String args, Instrumentation inst) {
        Map<String, List<Patch>> byOwner = PATCHES.stream().collect(Collectors.groupingBy(Patch::owner));
        inst.addTransformer(new ClassFileTransformer() {
            @Override
            public byte[] transform(Module module, ClassLoader loader, String name, Class<?> redefined,
                                    ProtectionDomain domain, byte[] bytes) {
                List<Patch> patches = name == null ? null : byOwner.get(name);
                if (patches == null) return null;
                LOADED.add(name);
                try {
                    byte[] out = Patcher.apply(bytes, patches, APPLIED);
                    Log.info("patched " + name.replace('/', '.') + " (" + patches.size() + " method(s))");
                    return out;
                } catch (Throwable t) {
                    // Never take the server down over a patch: run that class unpatched.
                    Log.error("could not patch " + name + "; running it unpatched", t);
                    return null;
                }
            }
        });
        Log.info("Splint " + VERSION + " agent loaded (" + PATCHES.size() + " patches registered)");
    }

    /**
     * Patches whose class has loaded but whose method was not found, e.g. after a game update
     * changed its signature. Classes that have not loaded yet are not counted.
     */
    public static List<String> missing() {
        return PATCHES.stream().filter(p -> LOADED.contains(p.owner()))
                .map(p -> p.owner() + "." + p.name() + p.descriptor())
                .filter(k -> !APPLIED.contains(k)).toList();
    }
}
