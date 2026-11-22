// Offline check: applies every Splint patch to the installed game jar and runs the JDK verifier.
// Usage: python3 build.py --verify
import java.lang.classfile.ClassFile;
import java.lang.reflect.Field;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipFile;

public class VerifyPatches {
    public static void main(String[] a) throws Exception {
        Class<?> agent = Class.forName("splint.agent.SplintAgent");
        Field f = agent.getDeclaredField("PATCHES"); f.setAccessible(true);
        @SuppressWarnings("unchecked") List<splint.agent.Patcher.Patch> patches = (List<splint.agent.Patcher.Patch>) f.get(null);
        Map<String, List<splint.agent.Patcher.Patch>> byOwner = new TreeMap<>();
        for (var p : patches) byOwner.computeIfAbsent(p.owner(), k -> new ArrayList<>()).add(p);
        Set<String> applied = new TreeSet<>();
        int bad = 0;
        try (ZipFile zip = new ZipFile(a[0])) {
            for (var e : byOwner.entrySet()) {
                byte[] in = zip.getInputStream(zip.getEntry(e.getKey() + ".class")).readAllBytes();
                byte[] out = splint.agent.Patcher.apply(in, e.getValue(), applied);
                var errors = ClassFile.of().verify(out);
                System.out.println(e.getKey() + ": " + in.length + " -> " + out.length + " bytes, verify errors=" + errors.size());
                for (var err : errors) { System.out.println("   " + err.getMessage()); bad++; }
            }
        }
        for (var p : patches) {
            String k = p.owner() + "." + p.name() + p.descriptor();
            if (!applied.contains(k)) { System.out.println("NOT APPLIED " + k); bad++; }
        }
        System.out.println(applied.size() + " of " + patches.size() + " patches applied");
        System.exit(bad == 0 ? 0 : 1);
    }
}
