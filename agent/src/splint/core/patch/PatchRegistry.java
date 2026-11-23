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
    private record Declared(String module, String owner, String key) {}

    private static final List<Declared> DECLARED = new ArrayList<>();
    private static final Set<String> APPLIED = ConcurrentHashMap.newKeySet();
    private static final Set<String> LOADED = ConcurrentHashMap.newKeySet();
    /** Target classes that were already loaded when the agent started, so cannot be patched. */
    private static final Set<String> TOO_EARLY = ConcurrentHashMap.newKeySet();

    private PatchRegistry() {}

    public static synchronized void install(List<SplintModule> modules, Instrumentation inst) {
        Map<String, List<Patch>> patches = new HashMap<>();
        Map<String, List<Redirect>> redirects = new HashMap<>();
        Map<String, List<Patcher.Field>> fields = new HashMap<>();
        Map<String, List<Patcher.CallSite>> sites = new HashMap<>();
        for (SplintModule m : modules) {
            for (Patch p : m.patches()) {
                DECLARED.add(new Declared(m.id(), p.owner(), p.owner() + "." + p.name() + p.descriptor()));
                patches.computeIfAbsent(p.owner(), k -> new ArrayList<>()).add(p);
            }
            for (Redirect r : m.redirects()) {
                DECLARED.add(new Declared(m.id(), r.owner(), r.key()));
                redirects.computeIfAbsent(r.owner(), k -> new ArrayList<>()).add(r);
            }
            for (Patcher.CallSite c : m.callSites()) {
                DECLARED.add(new Declared(m.id(), c.owner(), c.key()));
                sites.computeIfAbsent(c.owner(), k -> new ArrayList<>()).add(c);
            }
            for (Patcher.Field f : m.fields()) {
                DECLARED.add(new Declared(m.id(), f.owner(), f.key()));
                fields.computeIfAbsent(f.owner(), k -> new ArrayList<>()).add(f);
            }
        }
        Set<String> targets = new java.util.HashSet<>(patches.keySet());
        targets.addAll(redirects.keySet());
        targets.addAll(fields.keySet());
        targets.addAll(sites.keySet());
        for (Class<?> c : inst.getAllLoadedClasses()) {
            String n = c.getName().replace('.', '/');
            if (targets.contains(n)) {
                TOO_EARLY.add(n);
                Log.boot(n + " was loaded before Splint; its patches cannot apply", null);
            }
        }
        inst.addTransformer(new ClassFileTransformer() {
            @Override
            public byte[] transform(Module module, ClassLoader loader, String name, Class<?> redefined,
                                    ProtectionDomain domain, byte[] bytes) {
                if (name == null) return null;
                List<Patch> ps = patches.getOrDefault(name, List.of());
                List<Redirect> rs = redirects.getOrDefault(name, List.of());
                List<Patcher.Field> fs = fields.getOrDefault(name, List.of());
                List<Patcher.CallSite> cs = sites.getOrDefault(name, List.of());
                if (ps.isEmpty() && rs.isEmpty() && fs.isEmpty() && cs.isEmpty()) return null;
                LOADED.add(name);
                byte[] out;
                try {
                    out = Patcher.apply(bytes, ps, rs, fs, cs, APPLIED);
                } catch (Throwable t) {
                    // Never take the game down over a patch: that class runs unpatched.
                    Log.boot("could not patch " + name + "; running it unpatched", t);
                    return null;
                }
                // The JVM discards a transformer's result if it throws, so report only
                // through the boot log, which cannot throw or call back into game code.
                Log.boot("patched " + name.replace('/', '.') + " (" + (ps.size() + rs.size() + fs.size() + cs.size()) + " change(s))", null);
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
            boolean seen = LOADED.contains(d.owner()) || TOO_EARLY.contains(d.owner());
            if (seen && !APPLIED.contains(d.key())) out.add(d.module() + ": " + d.key());
        }
        return out;
    }

    /** Declared patches whose class has not loaded yet (normal early on; they apply when it does). */
    public static synchronized int pending() {
        int n = 0;
        for (Declared d : DECLARED) if (!LOADED.contains(d.owner()) && !TOO_EARLY.contains(d.owner())) n++;
        return n;
    }

    /** "21 of 21 patches applied, none missing" (or pending / missing details). */
    public static String summary() {
        List<String> missing = missing();
        int pending = pending();
        return applied() + " of " + total() + " patches applied" + (pending > 0 ? " (" + pending + " waiting for their class to load)" : "")
                + (missing.isEmpty() ? ", none missing" : ", MISSING " + missing);
    }
}
