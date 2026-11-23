package splint.core.patch;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import splint.core.log.Log;
import splint.core.module.SplintModule;
import splint.core.patch.Patcher.Patch;
import splint.core.patch.Patcher.Redirect;

/**
 * Every patch any active module declared, which of them were applied, and the transformer
 * that applies them as the game's classes load. {@code /splint status} reads it.
 */
public final class PatchRegistry {
    private record Declared(String module, Patch patch, Redirect redirect) {
        String owner() { return patch != null ? patch.owner() : redirect.owner(); }
        String key() { return patch != null ? patch.owner() + "." + patch.name() + patch.descriptor() : redirect.key(); }
    }

    private static final List<Declared> DECLARED = new ArrayList<>();
    private static final Set<String> APPLIED = ConcurrentHashMap.newKeySet();
    private static final Set<String> LOADED = ConcurrentHashMap.newKeySet();

    private PatchRegistry() {}

    public static synchronized void install(List<SplintModule> modules, Instrumentation inst) {
        Map<String, List<Patch>> patches = new HashMap<>();
        Map<String, List<Redirect>> redirects = new HashMap<>();
        for (SplintModule m : modules) {
            for (Patch p : m.patches()) {
                DECLARED.add(new Declared(m.id(), p, null));
                patches.computeIfAbsent(p.owner(), k -> new ArrayList<>()).add(p);
            }
            for (Redirect r : m.redirects()) {
                DECLARED.add(new Declared(m.id(), null, r));
                redirects.computeIfAbsent(r.owner(), k -> new ArrayList<>()).add(r);
            }
        }
        inst.addTransformer(new ClassFileTransformer() {
            @Override
            public byte[] transform(Module module, ClassLoader loader, String name, Class<?> redefined,
                                    ProtectionDomain domain, byte[] bytes) {
                if (name == null) return null;
                List<Patch> ps = patches.getOrDefault(name, List.of());
                List<Redirect> rs = redirects.getOrDefault(name, List.of());
                if (ps.isEmpty() && rs.isEmpty()) return null;
                LOADED.add(name);
                byte[] out;
                try {
                    out = Patcher.apply(bytes, ps, rs, APPLIED);
                } catch (Throwable t) {
                    // Never take the game down over a patch: that class runs unpatched.
                    Log.boot("could not patch " + name + "; running it unpatched", t);
                    return null;
                }
                // The JVM discards a transformer's result if it throws, so report only
                // through the boot log, which cannot throw or call back into game code.
                Log.boot("patched " + name.replace('/', '.') + " (" + (ps.size() + rs.size()) + " change(s))", null);
                return out;
            }
        });
    }

    public static synchronized int total() { return DECLARED.size(); }

    public static int applied() { return APPLIED.size(); }

    /**
     * Patches whose class has loaded but whose target was not found, e.g. after a game update
     * changed a method. Classes that have not loaded yet are not counted.
     */
    public static synchronized List<String> missing() {
        List<String> out = new ArrayList<>();
        for (Declared d : DECLARED) {
            if (LOADED.contains(d.owner()) && !APPLIED.contains(d.key())) out.add(d.module() + ": " + d.key());
        }
        return out;
    }

    /** "16 of 16 patches applied, none missing" (or the missing list). */
    public static String summary() {
        List<String> missing = missing();
        return applied() + " of " + total() + " patches applied" + (missing.isEmpty() ? ", none missing" : ", MISSING " + missing);
    }
}
