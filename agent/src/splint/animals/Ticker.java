package splint.animals;

import splint.agent.SplintAgent;
import splint.core.Log;

/** Start-up report and a once-a-minute summary while anything is happening. */
final class Ticker {
    private static final long SUMMARY_MS = 60_000;
    private static boolean started;
    private static long nextSummary;
    private static String lastSummary = "";

    private Ticker() {}

    static void tick() {
        long now = System.currentTimeMillis();
        if (!started) {
            started = true;
            nextSummary = now + SUMMARY_MS;
            Log.info("Splint " + SplintAgent.VERSION + " active: " + SplintAgent.APPLIED.size() + " methods patched; "
                    + AnimalIds.status());
            var missing = SplintAgent.missing();
            if (!missing.isEmpty()) Log.warn("patches not applied (game version changed?): " + missing);
            return;
        }
        if (now < nextSummary) return;
        nextSummary = now + SUMMARY_MS;
        String summary = Stats.summary();
        if (!summary.equals(lastSummary)) {
            lastSummary = summary;
            Log.info("status: " + summary + " frozen=" + Quarantine.frozen().size());
        }
    }
}
