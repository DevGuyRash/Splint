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
    private static final MethodHandle ORIGINAL_FIND;
    private static final MethodHandle ORIGINAL_SUBCLASSES;

    static {
        try {
            var lookup = MethodHandles.publicLookup();
            ORIGINAL_FIND = lookup.findStatic(CommandBase.class, Patcher.ORIGINAL_PREFIX + "findCommandCls",
                    MethodType.methodType(Class.class, String.class));
            ORIGINAL_SUBCLASSES = lookup.findStatic(CommandBase.class, Patcher.ORIGINAL_PREFIX + "getSubClasses",
                    MethodType.methodType(Class[].class));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private CommandHooks() {}

    public static Class<?> findCommandCls(String command) throws Throwable {
        Log.gameStarted();
        if (command != null && SPLINT.matcher(command.stripLeading()).find()) return SplintCommand.class;
        return (Class<?>) ORIGINAL_FIND.invokeExact(command);
    }

    public static Class<?>[] getSubClasses() throws Throwable {
        Class<?>[] vanilla = (Class<?>[]) ORIGINAL_SUBCLASSES.invokeExact();
        Class<?>[] out = Arrays.copyOf(vanilla, vanilla.length + 1);
        out[vanilla.length] = SplintCommand.class;
        return out;
    }
}
