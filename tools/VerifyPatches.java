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
        // Every hook and redirect target must exist as a public static method with the exact
        // descriptor the rewritten game method calls; otherwise that game method throws
        // NoSuchMethodError on every call, outside any hook's protection.
        for (SplintModule m : ServiceLoader.load(SplintModule.class)) {
            for (var p : m.patches()) {
                var type = java.lang.constant.MethodTypeDesc.ofDescriptor(p.descriptor());
                var params = new ArrayList<java.lang.constant.ClassDesc>();
                if (!isStaticInGame(p.owner(), p.name(), p.descriptor())) {
                    params.add(p.objectSelf() ? java.lang.constant.ConstantDescs.CD_Object : java.lang.constant.ClassDesc.ofInternalName(p.owner()));
                }
                params.addAll(type.parameterList());
                bad += checkStatic(p.hookClass(), p.hookName(), java.lang.constant.MethodTypeDesc.of(type.returnType(), params.toArray(java.lang.constant.ClassDesc[]::new)));
            }
            for (var r : m.redirects()) {
                var type = java.lang.constant.MethodTypeDesc.ofDescriptor(r.callDesc());
                if (r.passThis()) {
                    var params = new ArrayList<java.lang.constant.ClassDesc>();
                    params.add(java.lang.constant.ClassDesc.ofInternalName(r.owner()));
                    params.addAll(type.parameterList());
                    type = java.lang.constant.MethodTypeDesc.of(type.returnType(), params.toArray(java.lang.constant.ClassDesc[]::new));
                }
                bad += checkStatic(r.newOwner(), r.newName(), type);
            }
        }
        System.out.println(applied.size() + " of " + expected.size() + " patches applied");
        System.exit(bad == 0 ? 0 : 1);
    }

    /** Whether the game method a patch replaces is static (its hook then has no self parameter). */
    private static boolean isStaticInGame(String owner, String name, String descriptor) throws Exception {
        Class<?> c = Class.forName(owner.replace('/', '.'), false, VerifyPatches.class.getClassLoader());
        for (var mth : c.getDeclaredMethods()) {
            String desc = java.lang.invoke.MethodType.methodType(mth.getReturnType(), mth.getParameterTypes()).toMethodDescriptorString();
            if (mth.getName().equals(name) && desc.equals(descriptor)) return java.lang.reflect.Modifier.isStatic(mth.getModifiers());
        }
        throw new IllegalStateException("no " + owner + "." + name + descriptor + " in the game");
    }

    private static int checkStatic(String owner, String name, java.lang.constant.MethodTypeDesc want) throws Exception {
        Class<?> c = Class.forName(owner.replace('/', '.'), false, VerifyPatches.class.getClassLoader());
        for (var mth : c.getDeclaredMethods()) {
            if (!mth.getName().equals(name)) continue;
            String desc = java.lang.invoke.MethodType.methodType(mth.getReturnType(), mth.getParameterTypes()).toMethodDescriptorString();
            int mod = mth.getModifiers();
            if (desc.equals(want.descriptorString()) && java.lang.reflect.Modifier.isStatic(mod) && java.lang.reflect.Modifier.isPublic(mod)) return 0;
        }
        System.out.println("MISSING HOOK " + owner + "." + name + want.descriptorString());
        return 1;
    }
}
