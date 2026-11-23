package splint.core.module;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.stream.Collectors;
import splint.core.log.Log;

/** The single list of active modules, decided once at start-up. */
public final class Modules {
    private static List<SplintModule> active = List.of();
    private static List<String> skipped = List.of();
    private static Side side = Side.UNKNOWN;

    private Modules() {}

    public static synchronized void load() {
        side = Side.detect();
        Set<String> only = ids(System.getProperty("splint.modules", ""));
        Set<String> disabled = ids(System.getProperty("splint.disable", ""));
        List<SplintModule> on = new ArrayList<>();
        List<String> off = new ArrayList<>();
        for (SplintModule m : ServiceLoader.load(SplintModule.class, Modules.class.getClassLoader())) {
            String id = m.id();
            boolean core = id.equals("core");
            if (!core && (disabled.contains(id) || (!only.isEmpty() && !only.contains(id)))) {
                off.add(id + " (disabled)");
            } else if (!side.runs(m.side())) {
                off.add(id + " (" + m.side().name().toLowerCase(Locale.ROOT) + " only)");
            } else {
                on.add(m);
            }
        }
        active = List.copyOf(on);
        skipped = List.copyOf(off);
        Log.boot("side " + side.name().toLowerCase(Locale.ROOT) + "; modules " + active.stream().map(SplintModule::id).toList()
                + (skipped.isEmpty() ? "" : "; skipped " + skipped), null);
    }

    public static List<SplintModule> active() { return active; }

    public static List<String> skipped() { return skipped; }

    public static Side side() { return side; }

    private static Set<String> ids(String csv) {
        return Arrays.stream(csv.split(",")).map(s -> s.trim().toLowerCase(Locale.ROOT)).filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
    }
}
