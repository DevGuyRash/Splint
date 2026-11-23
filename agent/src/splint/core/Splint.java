package splint.core;

import splint.core.log.Log;
import splint.core.module.Modules;
import splint.core.module.SplintModule;
import splint.core.patch.PatchRegistry;

/** Version and the per-frame heartbeat that drives every module. */
public final class Splint {
    public static final String VERSION = "0.2.0";
    private static final long SUMMARY_MS = 60_000;

    private static boolean started;
    private static long nextSummary;
    private static String lastSummary = "";

    private Splint() {}

    /** Called once per world update on the main thread (see CoreHooks.worldUpdate). */
    public static void tick() {
        long now = System.currentTimeMillis();
        if (!started) {
            started = true;
            Log.gameStarted();
            nextSummary = now + SUMMARY_MS;
            Log.info("Splint " + VERSION + " active: " + PatchRegistry.summary() + "; modules "
                    + Modules.active().stream().map(SplintModule::id).toList());
        }
        for (SplintModule m : Modules.active()) {
            try {
                m.tick(now);
            } catch (Throwable t) {
                Log.problem("module-tick:" + m.id(), "module " + m.id() + " tick threw; contained", t);
            }
        }
        if (now >= nextSummary) {
            nextSummary = now + SUMMARY_MS;
            String summary = Counters.summary();
            if (!summary.equals(lastSummary)) {
                lastSummary = summary;
                Log.info("status: " + summary.replace('\n', ' '));
            }
        }
    }
}
