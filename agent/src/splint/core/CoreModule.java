package splint.core;

import java.util.List;
import splint.core.module.SplintModule;
import splint.core.patch.Patcher.Patch;

/** Splint's own plumbing: the per-frame heartbeat and the /splint command. Always on. */
public final class CoreModule implements SplintModule {
    private static final String CORE_HOOKS = "splint/core/CoreHooks";
    private static final String COMMAND_HOOKS = "splint/core/command/CommandHooks";
    private static final String COMMAND_BASE = "zombie/commands/CommandBase";

    @Override
    public String id() { return "core"; }

    @Override
    public String description() { return "heartbeat, logging, incident history, /splint command"; }

    @Override
    public List<Patch> patches() {
        return List.of(
                new Patch("zombie/iso/IsoWorld", "update", "()V", CORE_HOOKS, "worldUpdate"),
                new Patch(COMMAND_BASE, "findCommandCls", "(Ljava/lang/String;)Ljava/lang/Class;", COMMAND_HOOKS, "findCommandCls"),
                new Patch(COMMAND_BASE, "getSubClasses", "()[Ljava/lang/Class;", COMMAND_HOOKS, "getSubClasses"));
    }
}
