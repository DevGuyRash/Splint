package splint.core;

import java.util.List;
import splint.core.module.SplintModule;
import splint.core.command.ModuleCommand;
import splint.core.patch.Patcher.CallSite;
import splint.core.patch.Patcher.Field;
import splint.core.patch.Patcher.Patch;

/**
 * Splint's own plumbing, always on: the per-frame heartbeat, the /splint command, and per-object
 * isolation of the update scheduler (one broken object of any kind cannot stop the world).
 */
public final class CoreModule implements SplintModule {
    private static final String CORE_HOOKS = "splint/core/CoreHooks";
    private static final String COMMAND_HOOKS = "splint/core/command/CommandHooks";
    private static final String COMMAND_BASE = "zombie/commands/CommandBase";

    @Override
    public String id() { return "core"; }

    private static final String BUCKET = "zombie/MovingObjectUpdateSchedulerUpdateBucket";
    private static final String MOVING = "zombie/iso/IsoMovingObject";
    private static final String ISOLATION = "splint/core/isolate/Isolation";

    @Override
    public String description() { return "heartbeat, logging, incidents, per-object update isolation, /splint command"; }

    /**
     * The scheduler calls preupdate, frameStep and update, then postupdate, on every moving
     * object with nothing around them: one throw abandons the rest of the frame. Each call goes
     * through {@link splint.core.isolate.Isolation} instead.
     */
    @Override
    public List<CallSite> callSites() {
        return List.of(
                new CallSite(BUCKET, "update", "(I)V", MOVING, "preupdate", "()V", ISOLATION, "preupdate"),
                new CallSite(BUCKET, "update", "(I)V", MOVING, "frameStep", "()V", ISOLATION, "frameStep"),
                new CallSite(BUCKET, "update", "(I)V", MOVING, "update", "()V", ISOLATION, "update"),
                new CallSite(BUCKET, "postupdate", "(I)V", MOVING, "postupdate", "()V", ISOLATION, "postupdate"));
    }

    /** Per-object breaker state (see Breaker). */
    @Override
    public List<Field> fields() {
        return List.of(new Field(MOVING, splint.core.isolate.Breaker.FIELD, "Ljava/lang/Object;"));
    }

    /** Created on first use, never in premain (see SplintModule). */
    private static final class Commands {
        static final List<ModuleCommand> ALL = List.of(new splint.core.isolate.FrozenCommand());
    }

    @Override
    public List<ModuleCommand> commands() { return Commands.ALL; }

    @Override
    public List<Patch> patches() {
        return List.of(
                new Patch("zombie/iso/IsoWorld", "update", "()V", CORE_HOOKS, "worldUpdate"),
                new Patch(COMMAND_BASE, "findCommandCls", "(Ljava/lang/String;)Ljava/lang/Class;", COMMAND_HOOKS, "findCommandCls"),
                new Patch(COMMAND_BASE, "getSubClasses", "()[Ljava/lang/Class;", COMMAND_HOOKS, "getSubClasses"),
                // Zombie objects are pooled and reused; a reused zombie starts with a clean breaker.
                new Patch("zombie/characters/IsoZombie", "resetForReuse", "()V", CORE_HOOKS, "zombieReset"));
    }
}
