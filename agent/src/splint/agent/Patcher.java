package splint.agent;

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

    private Patcher() {}

    /** Returns the rewritten class, or {@code null} when none of the patches apply to it. */
    public static byte[] apply(byte[] classBytes, List<Patch> patches, Set<String> applied) {
        ClassFile cf = ClassFile.of();
        ClassModel model = cf.parse(classBytes);
        Map<String, Patch> byKey = new java.util.HashMap<>();
        for (Patch p : patches) byKey.put(p.key(), p);
        Set<String> present = new HashSet<>();
        for (MethodModel m : model.methods()) {
            String key = m.methodName().stringValue() + m.methodType().stringValue();
            if (byKey.containsKey(key)) present.add(key);
        }
        if (present.isEmpty()) return null;
        ClassDesc owner = model.thisClass().asSymbol();
        ClassTransform transform = (cb, element) -> {
            if (!(element instanceof MethodModel method)) {
                cb.with(element);
                return;
            }
            String key = method.methodName().stringValue() + method.methodType().stringValue();
            Patch patch = byKey.get(key);
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
        return cf.transformClass(model, transform);
    }

    private static boolean isAnnotation(MethodElement me) {
        return me instanceof RuntimeVisibleAnnotationsAttribute
                || me instanceof RuntimeInvisibleAnnotationsAttribute
                || me instanceof RuntimeVisibleParameterAnnotationsAttribute
                || me instanceof RuntimeInvisibleParameterAnnotationsAttribute;
    }
}
