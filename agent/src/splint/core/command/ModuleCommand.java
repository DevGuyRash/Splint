package splint.core.command;

import java.util.List;

/** A {@code /splint <name> ...} command a module contributes. */
public interface ModuleCommand {
    /** First word after /splint. */
    String name();

    default List<String> aliases() { return List.of(); }

    /** Lines for /splint help (and the full text for /splint NAME help). */
    String help();

    /** Runs with the words after the command name; throw {@link Usage} for bad input. */
    String run(CommandContext ctx, Args args);
}
