// Offline check: applies every active module's patches to the installed game jar and runs the
// JDK verifier on each rewritten class. Usage: python3 build.py --verify
import java.lang.classfile.ClassFile;
import java.util.*;
import java.util.zip.ZipFile;
import splint.core.module.SplintModule;
import splint.core.patch.Patcher;

public class VerifyPatches {
    public static void main(String[] a) throws Exception {
        Map<String, List<Patcher.Patch>> patches = new TreeMap<>();
        Map<String, List<Patcher.Redirect>> redirects = new TreeMap<>();
        List<String> expected = new ArrayList<>();
        for (SplintModule m : ServiceLoader.load(SplintModule.class)) {
            for (var p : m.patches()) {
                patches.computeIfAbsent(p.owner(), k -> new ArrayList<>()).add(p);
                expected.add(p.owner() + "." + p.name() + p.descriptor());
            }
            for (var r : m.redirects()) {
                redirects.computeIfAbsent(r.owner(), k -> new ArrayList<>()).add(r);
                expected.add(r.key());
            }
            System.out.println("module " + m.id() + ": " + m.patches().size() + " patches, " + m.redirects().size() + " redirects");
        }
        Set<String> owners = new TreeSet<>(patches.keySet());
        owners.addAll(redirects.keySet());
        Set<String> applied = new TreeSet<>();
        int bad = 0;
        try (ZipFile zip = new ZipFile(a[0])) {
            for (String owner : owners) {
                var entry = zip.getEntry(owner + ".class");
                if (entry == null) { System.out.println("NO CLASS " + owner); bad++; continue; }
                byte[] in = zip.getInputStream(entry).readAllBytes();
                byte[] out = Patcher.apply(in, patches.getOrDefault(owner, List.of()), redirects.getOrDefault(owner, List.of()), applied);
                var errors = ClassFile.of().verify(out);
                System.out.println(owner + ": " + in.length + " -> " + out.length + " bytes, verify errors=" + errors.size());
                for (var err : errors) { System.out.println("   " + err.getMessage()); bad++; }
            }
        }
        for (String k : expected) if (!applied.contains(k)) { System.out.println("NOT APPLIED " + k); bad++; }
        System.out.println(applied.size() + " of " + expected.size() + " patches applied");
        System.exit(bad == 0 ? 0 : 1);
    }
}
