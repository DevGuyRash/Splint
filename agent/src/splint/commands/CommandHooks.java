package splint.commands;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Arrays;
import java.util.regex.Pattern;
import splint.agent.Patcher;
import splint.core.Log;
import zombie.commands.CommandBase;

/**
 * The game finds commands in a fixed array inside CommandBase. These hooks add Splint's
 * command to that lookup and to the help listing; every other command is untouched.
 */
public final class CommandHooks {
    private static final Pattern SPLINT = Pattern.compile("^splint\\b", Pattern.CASE_INSENSITIVE);
    // Separate holders: each resolves only when its own (applied) patch first runs.
    private static final class Find {
        static final MethodHandle H = original("findCommandCls", MethodType.methodType(Class.class, String.class));
    }

    private static final class SubClasses {
        static final MethodHandle H = original("getSubClasses", MethodType.methodType(Class[].class));
    }

    private static MethodHandle original(String name, MethodType type) {
        try {
            return MethodHandles.publicLookup().findStatic(CommandBase.class, Patcher.ORIGINAL_PREFIX + name, type);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private CommandHooks() {}

    public static Class<?> findCommandCls(String command) throws Throwable {
        Log.gameStarted();
        if (command != null && SPLINT.matcher(command.stripLeading()).find()) return SplintCommand.class;
        return (Class<?>) Find.H.invokeExact(command);
    }

    public static Class<?>[] getSubClasses() throws Throwable {
        Class<?>[] vanilla = (Class<?>[]) SubClasses.H.invokeExact();
        Class<?>[] out = Arrays.copyOf(vanilla, vanilla.length + 1);
        out[vanilla.length] = SplintCommand.class;
        return out;
    }
}
