package splint.core.patch;

import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.ClassTransform;
import java.lang.classfile.MethodElement;
import java.lang.classfile.MethodModel;
import java.lang.classfile.TypeKind;
import java.lang.classfile.attribute.RuntimeInvisibleAnnotationsAttribute;
import java.lang.classfile.attribute.RuntimeVisibleAnnotationsAttribute;
import java.lang.classfile.attribute.RuntimeVisibleParameterAnnotationsAttribute;
import java.lang.classfile.attribute.RuntimeInvisibleParameterAnnotationsAttribute;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.lang.reflect.AccessFlag;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Rewrites game classes so a patched method calls a static hook, and keeps the original body
 * under a new name the hook can still call.
 *
 * <p>For a patch on {@code Owner.m(A, B)R} the class ends up with:
 * <ul>
 *   <li>{@code splint$m(A, B)R}: the original code, unchanged, made public so the hook can
 *       reach it through a method handle;</li>
 *   <li>{@code m(A, B)R}: same name, descriptor, flags and annotations as before, whose body is
 *       {@code return Hook.m(this, a, b);}.</li>
 * </ul>
 * Callers, overriders and Lua exposure therefore see the same method as before. Only the
 * method bodies named in a {@link Patch} change; every other byte of the class is copied.
 */
public final class Patcher {
    public static final String ORIGINAL_PREFIX = "splint$";

    /** One method to redirect. {@code hookName} is a static method on {@code hookClass}. */
    public record Patch(String owner, String name, String descriptor, String hookClass, String hookName,
                        boolean objectSelf) {
        /** A patch whose hook takes the owner as its declared type. */
        public Patch(String owner, String name, String descriptor, String hookClass, String hookName) {
            this(owner, name, descriptor, hookClass, hookName, false);
        }

        String key() { return name + descriptor; }
    }

    /**
     * Inside {@code owner.method}, replaces a static call {@code callOwner.callName callDesc}
     * whose result is stored straight into the field {@code storeField} of the class, with a
     * call to the static {@code newOwner.newName}. With {@code passThis} the receiver
     * ({@code this}) is passed first, followed by the original argument, so the replacement
     * knows which object the value is for and can still do what vanilla did: its descriptor is
     * then {@code (Lowner;A)R} for {@code callDesc (A)R}. passThis supports calls with no
     * argument or one single-slot argument.
     * Used where vanilla computes a value badly in one expression (the animal ID) and the fix
     * is to compute it correctly there, not to overwrite it afterwards.
     */
    public record Redirect(String owner, String method, String methodDesc, String callOwner, String callName,
                           String callDesc, String storeField, String newOwner, String newName, boolean passThis) {
        public Redirect {
            if (passThis) {
                MethodTypeDesc d = MethodTypeDesc.ofDescriptor(callDesc);
                if (d.parameterCount() > 1 || (d.parameterCount() == 1 && TypeKind.from(d.parameterType(0)).slotSize() != 1)) {
                    throw new IllegalArgumentException("passThis redirect needs at most one single-slot argument: " + callDesc);
                }
            }
        }

        public String key() { return owner + "." + method + methodDesc + "@" + callOwner + "." + callName; }
    }

    /**
     * Inside {@code owner.method}, replaces every {@code invokevirtual callOwner.callName callDesc}
     * with {@code invokestatic newOwner.newName}, whose descriptor takes the receiver first:
     * {@code (LcallOwner;A...)R}. The stack is unchanged, so this needs no lookahead. Used to
     * wrap calls a game loop makes on each object (the update scheduler) without patching every
     * subclass that overrides them.
     */
    public record CallSite(String owner, String method, String methodDesc, String callOwner, String callName, String callDesc,
                           String newOwner, String newName) {
        public String key() { return owner + "." + method + methodDesc + "->" + callOwner + "." + callName + callDesc; }
    }

    /** A public field Splint adds to a game class, for per-object state (see {@code splint.core.isolate.Breaker}). */
    public record Field(String owner, String name, String descriptor) {
        public String key() { return owner + "#" + name; }
    }

    private Patcher() {}

    /** Returns the rewritten class, or {@code null} when none of the patches apply to it. */
    public static byte[] apply(byte[] classBytes, List<Patch> patches, List<Redirect> redirects, Set<String> applied) {
        return apply(classBytes, patches, redirects, List.of(), applied);
    }

    /**
     * Rewrites one class. {@code applied} receives the keys of what was applied, but only once
     * the whole class transformed successfully.
     */
    public static byte[] apply(byte[] classBytes, List<Patch> patches, List<Redirect> redirects, List<Field> fields,
                               Set<String> applied) {
        return apply(classBytes, patches, redirects, fields, List.of(), applied);
    }

    public static byte[] apply(byte[] classBytes, List<Patch> patches, List<Redirect> redirects, List<Field> fields,
                               List<CallSite> callSites, Set<String> applied) {
        Set<String> done = new HashSet<>();
        byte[] out = transform(classBytes, patches, redirects, fields, callSites, done);
        if (out != null) applied.addAll(done);
        return out;
    }

    private static byte[] transform(byte[] classBytes, List<Patch> patches, List<Redirect> redirects, List<Field> fields,
                                    List<CallSite> callSites, Set<String> applied) {
        ClassFile cf = ClassFile.of();
        ClassModel model = cf.parse(classBytes);
        Map<String, Patch> byKey = new java.util.HashMap<>();
        for (Patch p : patches) byKey.put(p.key(), p);
        Map<String, List<Redirect>> redirectsByMethod = new java.util.HashMap<>();
        for (Redirect r : redirects) redirectsByMethod.computeIfAbsent(r.method() + r.methodDesc(), k -> new ArrayList<>()).add(r);
        Map<String, List<CallSite>> sitesByMethod = new java.util.HashMap<>();
        for (CallSite c : callSites) sitesByMethod.computeIfAbsent(c.method() + c.methodDesc(), k -> new ArrayList<>()).add(c);
        Set<String> present = new HashSet<>();
        for (MethodModel m : model.methods()) {
            String key = m.methodName().stringValue() + m.methodType().stringValue();
            if (byKey.containsKey(key) || redirectsByMethod.containsKey(key) || sitesByMethod.containsKey(key)) present.add(key);
        }
        if (present.isEmpty() && fields.isEmpty()) return null;
        ClassDesc owner = model.thisClass().asSymbol();
        ClassTransform methods = (cb, element) -> {
            if (!(element instanceof MethodModel method)) {
                cb.with(element);
                return;
            }
            String key = method.methodName().stringValue() + method.methodType().stringValue();
            Patch patch = byKey.get(key);
            List<Redirect> rs = redirectsByMethod.get(key);
            List<CallSite> cs = sitesByMethod.get(key);
            if (patch == null && (rs != null || cs != null)) {
                java.lang.classfile.CodeTransform code = java.lang.classfile.CodeTransform.ACCEPT_ALL;
                if (rs != null) code = java.lang.classfile.CodeTransform.ofStateful(() -> new RedirectCalls(rs, owner, applied));
                if (cs != null) code = code.andThen(new SiteCalls(cs, applied));
                cb.transformMethod(method, java.lang.classfile.MethodTransform.transformingCode(code));
                return;
            }
            if (patch == null) {
                cb.with(element);
                return;
            }
            int flags = method.flags().flagsMask();
            boolean isStatic = method.flags().has(AccessFlag.STATIC);
            // The original body, public and without annotations, under the prefixed name.
            int originalFlags = (flags & ~(ClassFile.ACC_PRIVATE | ClassFile.ACC_PROTECTED)) | ClassFile.ACC_PUBLIC
                    | ClassFile.ACC_SYNTHETIC;
            cb.withMethod(ORIGINAL_PREFIX + method.methodName().stringValue(), method.methodTypeSymbol(), originalFlags, mb -> {
                for (MethodElement me : method) {
                    if (me instanceof java.lang.classfile.AccessFlags) continue;
                    if (isAnnotation(me)) continue;
                    mb.with(me);
                }
            });
            // The replacement: same name/descriptor/flags/annotations, body delegates to the hook.
            MethodTypeDesc type = method.methodTypeSymbol();
            List<ClassDesc> hookParams = new ArrayList<>();
            if (!isStatic) hookParams.add(patch.objectSelf() ? java.lang.constant.ConstantDescs.CD_Object : owner);
            hookParams.addAll(type.parameterList());
            MethodTypeDesc hookType = MethodTypeDesc.of(type.returnType(), hookParams.toArray(ClassDesc[]::new));
            ClassDesc hookOwner = ClassDesc.ofInternalName(patch.hookClass());
            cb.withMethod(method.methodName().stringValue(), type, flags & ~ClassFile.ACC_ABSTRACT & ~ClassFile.ACC_NATIVE, mb -> {
                for (MethodElement me : method) {
                    if (isAnnotation(me) || me instanceof java.lang.classfile.attribute.ExceptionsAttribute) mb.with(me);
                }
                mb.withCode(code -> {
                    int slot = 0;
                    if (!isStatic) {
                        code.aload(0);
                        slot = 1;
                    }
                    for (ClassDesc param : type.parameterList()) {
                        TypeKind kind = TypeKind.from(param);
                        code.loadLocal(kind, slot);
                        slot += kind.slotSize();
                    }
                    code.invokestatic(hookOwner, patch.hookName(), hookType);
                    code.return_(TypeKind.from(type.returnType()));
                });
            });
            applied.add(patch.owner() + "." + key);
        };
        ClassTransform withFields = methods.andThen(ClassTransform.endHandler(cb -> {
            for (Field f : fields) {
                cb.withField(f.name(), ClassDesc.ofDescriptor(f.descriptor()),
                        ClassFile.ACC_PUBLIC | ClassFile.ACC_SYNTHETIC | ClassFile.ACC_TRANSIENT);
                applied.add(f.key());
            }
        }));
        return cf.transformClass(model, withFields);
    }

    /**
     * Holds back a matching invoke until the next instruction shows whether its result goes
     * straight into the target field; only then is the call replaced.
     */
    private static final class RedirectCalls implements java.lang.classfile.CodeTransform {
        private final List<Redirect> redirects;
        private final ClassDesc owner;
        private final Set<String> applied;
        private java.lang.classfile.instruction.InvokeInstruction held;
        private Redirect heldFor;
        /** Markers (line numbers, labels) seen after the held call, replayed in order. */
        private final List<java.lang.classfile.CodeElement> markers = new ArrayList<>();

        RedirectCalls(List<Redirect> redirects, ClassDesc owner, Set<String> applied) {
            this.redirects = redirects;
            this.owner = owner;
            this.applied = applied;
        }

        @Override
        public void accept(java.lang.classfile.CodeBuilder b, java.lang.classfile.CodeElement e) {
            if (held != null && !(e instanceof java.lang.classfile.Instruction)) {
                markers.add(e);
                return;
            }
            if (held != null) {
                boolean stores = e instanceof java.lang.classfile.instruction.FieldInstruction f
                        && f.opcode() == java.lang.classfile.Opcode.PUTFIELD
                        && f.owner().asSymbol().equals(owner) && f.name().equalsString(heldFor.storeField());
                if (stores) emitReplacement(b, held, heldFor);
                else b.with(held);
                markers.forEach(b::with);
                markers.clear();
                held = null;
                heldFor = null;
            }
            if (e instanceof java.lang.classfile.instruction.InvokeInstruction inv
                    && inv.opcode() == java.lang.classfile.Opcode.INVOKESTATIC) {
                for (Redirect r : redirects) {
                    if (inv.owner().asInternalName().equals(r.callOwner()) && inv.name().equalsString(r.callName())
                            && inv.type().equalsString(r.callDesc())) {
                        held = inv;
                        heldFor = r;
                        return;
                    }
                }
            }
            b.with(e);
        }

        private void emitReplacement(java.lang.classfile.CodeBuilder b, java.lang.classfile.instruction.InvokeInstruction call,
                                     Redirect r) {
            MethodTypeDesc type = call.typeSymbol();
            if (r.passThis()) {
                // Stack: [... this(for putfield), arg?]. Put `this` under the argument so the
                // replacement receives (owner, arg): aload_0; swap. One single-slot argument at
                // most (checked when the redirect is declared).
                b.aload(0);
                if (type.parameterCount() == 1) b.swap();
                List<ClassDesc> params = new ArrayList<>();
                params.add(owner);
                params.addAll(type.parameterList());
                type = MethodTypeDesc.of(type.returnType(), params.toArray(ClassDesc[]::new));
            }
            b.invokestatic(ClassDesc.ofInternalName(r.newOwner()), r.newName(), type);
            applied.add(r.key());
        }

        @Override
        public void atEnd(java.lang.classfile.CodeBuilder b) {
            if (held != null) b.with(held);
            markers.forEach(b::with);
        }
    }

    private record SiteCalls(List<CallSite> sites, Set<String> applied) implements java.lang.classfile.CodeTransform {
        @Override
        public void accept(java.lang.classfile.CodeBuilder b, java.lang.classfile.CodeElement e) {
            if (e instanceof java.lang.classfile.instruction.InvokeInstruction inv
                    && inv.opcode() == java.lang.classfile.Opcode.INVOKEVIRTUAL) {
                for (CallSite c : sites) {
                    if (inv.owner().asInternalName().equals(c.callOwner()) && inv.name().equalsString(c.callName())
                            && inv.type().equalsString(c.callDesc())) {
                        MethodTypeDesc t = inv.typeSymbol();
                        List<ClassDesc> params = new ArrayList<>();
                        params.add(ClassDesc.ofInternalName(c.callOwner()));
                        params.addAll(t.parameterList());
                        b.invokestatic(ClassDesc.ofInternalName(c.newOwner()), c.newName(),
                                MethodTypeDesc.of(t.returnType(), params.toArray(ClassDesc[]::new)));
                        applied.add(c.key());
                        return;
                    }
                }
            }
            b.with(e);
        }
    }

    private static boolean isAnnotation(MethodElement me) {
        return me instanceof RuntimeVisibleAnnotationsAttribute
                || me instanceof RuntimeInvisibleAnnotationsAttribute
                || me instanceof RuntimeVisibleParameterAnnotationsAttribute
                || me instanceof RuntimeInvisibleParameterAnnotationsAttribute;
    }
}
