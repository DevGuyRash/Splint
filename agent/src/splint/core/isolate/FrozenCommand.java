package splint.core.isolate;

import java.util.List;
import splint.core.command.Args;
import splint.core.command.CommandContext;
import splint.core.command.ModuleCommand;
import zombie.iso.IsoMovingObject;

/** {@code /splint frozen [release] [confirm]}: objects of any kind frozen after repeated failures. */
public final class FrozenCommand implements ModuleCommand {
    private static final int LIST_LIMIT = 25;

    @Override
    public String name() { return "frozen"; }

    @Override
    public String help() {
        return "/splint frozen [release] [confirm]  objects frozen after repeated update errors (modules may offer removal)";
    }

    @Override
    public String run(CommandContext ctx, Args a) {
        List<IsoMovingObject> frozen = Breaker.frozenObjects();
        if (frozen.isEmpty()) return "Nothing is frozen.";
        if (a.more() && a.lower().equals("release")) {
            if (!a.confirmed()) return "Dry run: would release " + frozen.size() + " frozen objects. Add 'confirm'.";
            ctx.requireMainThread();
            frozen.forEach(Breaker::release);
            ctx.audit("frozen release: " + frozen.size());
            return "Released " + frozen.size() + " objects; they will be updated again.";
        }
        StringBuilder b = new StringBuilder(frozen.size() + " frozen objects:");
        for (int i = 0; i < frozen.size() && i < LIST_LIMIT; i++) {
            IsoMovingObject o = frozen.get(i);
            b.append('\n').append(Describers.describe(o)).append(" last error: ").append(Breaker.lastError(o));
        }
        if (frozen.size() > LIST_LIMIT) b.append("\n... ").append(frozen.size() - LIST_LIMIT).append(" more");
        return b.toString();
    }
}
