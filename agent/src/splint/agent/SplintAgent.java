package splint.agent;

import java.lang.instrument.Instrumentation;
import splint.core.Splint;
import splint.core.log.Log;
import splint.core.module.Modules;
import splint.core.patch.PatchRegistry;

/**
 * Java agent entry point. Add {@code -javaagent:java/splint.jar} to the game's JVM options
 * (the {@code vmArgs} list of ProjectZomboid64.json, or the java command of a start script).
 * Nothing here edits files: listed game methods are rewritten in memory as the JVM loads them.
 */
public final class SplintAgent {
    private SplintAgent() {}

    public static void premain(String args, Instrumentation inst) {
        Modules.load();
        PatchRegistry.install(Modules.active(), inst);
        Log.boot("Splint " + Splint.VERSION + " agent loaded (" + PatchRegistry.total() + " patches registered)", null);
    }
}
