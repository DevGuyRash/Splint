package splint.core.module;

import java.util.List;
import splint.core.command.ModuleCommand;
import splint.core.patch.Patcher.Patch;
import splint.core.patch.Patcher.Redirect;

/**
 * One area of fixes or features (animals today; vehicles, zombies, client rendering later).
 * A module only declares what it contributes; the core installs the patches, routes
 * {@code /splint <command>} to its commands, reports its counters and calls its tick.
 *
 * <p>Modules are found with {@link java.util.ServiceLoader} through
 * {@code META-INF/services/splint.core.module.SplintModule}, so a module can live in this jar
 * or in a separate jar on the agent's class path. They are enabled by default and can be
 * switched off with {@code -Dsplint.disable=id1,id2} (or only some enabled with
 * {@code -Dsplint.modules=id1,id2}).
 *
 * <p>Methods are called once, from the agent's premain, except {@link #tick} (main game
 * thread, once per world update) and {@link #status} (when an admin asks).
 */
public interface SplintModule {
    /** Short lowercase identifier, also the default command name: "animals". */
    String id();

    /** One line for {@code /splint modules}. */
    String description();

    /** Where the module's patches are meant to run. */
    default Side side() { return Side.BOTH; }

    /** Methods to replace with hooks. Each hook must contain a fix for a reproduced problem. */
    default List<Patch> patches() { return List.of(); }

    /** Expressions to replace in place. */
    default List<Redirect> redirects() { return List.of(); }

    /** Calls inside game methods to route through a static wrapper (e.g. per-object isolation). */
    default List<splint.core.patch.Patcher.CallSite> callSites() { return List.of(); }

    /** Fields to add to game classes, for per-object state (see {@link splint.core.ObjectSlot}). */
    default List<splint.core.patch.Patcher.Field> fields() { return List.of(); }

    /** Admin commands, reachable as {@code /splint <name> ...}. */
    default List<ModuleCommand> commands() { return List.of(); }

    /** Called on the main thread once per world update; must be cheap. */
    default void tick(long nowMs) {}

    /** Extra lines for {@code /splint status}. */
    default String status() { return ""; }

    /**
     * What this module knows about each listed player, for {@code /splint players}. Called once
     * per command with every player, so a module can scan once rather than once per player.
     */
    default java.util.Map<zombie.characters.IsoPlayer, String> aboutPlayers(List<zombie.characters.IsoPlayer> players) {
        return java.util.Map.of();
    }
}
