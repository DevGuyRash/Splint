package splint.core.command;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import splint.core.Counters;
import splint.core.Splint;
import splint.core.log.Incidents;
import splint.core.log.Log;
import splint.core.module.Modules;
import splint.core.module.SplintModule;
import splint.core.patch.PatchRegistry;
import splint.core.world.World;
import zombie.characters.Capability;
import zombie.characters.IsoPlayer;
import zombie.characters.Role;
import zombie.commands.CommandArgs;
import zombie.commands.CommandBase;
import zombie.commands.CommandHelp;
import zombie.commands.CommandName;
import zombie.commands.RequiredCapability;
import zombie.core.raknet.UdpConnection;

/**
 * {@code /splint ...}, the one game command Splint adds. It works from the server console,
 * RCON and in-game chat (admins only), answers only the admin who ran it, and routes
 * {@code /splint <name>} to the module command of that name.
 */
@CommandName(name = "splint")
@CommandArgs(varArgs = true)
@CommandHelp(helpText = "Splint fixes and admin tools. /splint help", shouldTranslated = false)
@RequiredCapability(requiredCapability = Capability.AnimalCheats)
public class SplintCommand extends CommandBase {
    private static final int PLAYER_LIST_LIMIT = 60;

    public SplintCommand(String username, Role userRole, String command, UdpConnection connection) {
        super(username, userRole, command, connection);
    }

    @Override
    protected String Command() {
        List<String> words = new ArrayList<>();
        for (int i = 0; i < getCommandArgsCount(); i++) words.add(getCommandArg(i));
        CommandContext ctx = new CommandContext(getExecutorUsername(), connection == null);
        String result;
        try {
            result = dispatch(ctx, words);
        } catch (Usage u) {
            result = u.getMessage();
        } catch (Throwable t) {
            Log.error("/splint " + String.join(" ", words) + " failed", t);
            result = "Splint command failed: " + t + " (details in the Splint log)";
        }
        // In-game chat breaks lines on " <LINE> " (as vanilla /help does); the console on \n.
        return connection == null ? result : result.replace("\n", " <LINE> ");
    }

    private static Map<String, ModuleCommand> commands() {
        Map<String, ModuleCommand> out = new LinkedHashMap<>();
        for (SplintModule m : Modules.active()) {
            for (ModuleCommand c : m.commands()) {
                out.put(c.name(), c);
                for (String alias : c.aliases()) out.putIfAbsent(alias, c);
            }
        }
        return out;
    }

    static String dispatch(CommandContext ctx, List<String> words) {
        if (words.isEmpty()) return help();
        String verb = words.get(0).toLowerCase(Locale.ROOT);
        Args rest = new Args(words.subList(1, words.size()));
        return switch (verb) {
            case "help" -> help();
            case "status" -> status();
            case "modules" -> modules();
            case "problems", "incidents" -> problems(rest);
            case "players" -> players(rest, ctx);
            default -> {
                ModuleCommand c = commands().get(verb);
                if (c == null) throw new Usage("Unknown /splint command '" + words.get(0) + "'.\n" + help());
                if (rest.more() && "help".equalsIgnoreCase(rest.peek())) yield c.help();
                yield c.run(ctx, rest);
            }
        };
    }

    static String help() {
        StringBuilder b = new StringBuilder("Splint " + Splint.VERSION + " (destructive commands are dry runs until you add 'confirm'):\n"
                + "/splint status                 patches, modules and counters\n"
                + "/splint modules                loaded modules\n"
                + "/splint problems [N]           what Splint handled: by kind, worst map cells (with who is there), last N\n"
                + "/splint players [AREA]         online players: position, map cell, what each module sees around them\n"
                + "AREA: " + AreaTerms.HELP + "  (map cell = floor(x/256), floor(y/256))");
        for (ModuleCommand c : new java.util.LinkedHashSet<>(commands().values())) b.append('\n').append(c.help());
        return b.toString();
    }

    static String status() {
        StringBuilder b = new StringBuilder("Splint " + Splint.VERSION + ": " + PatchRegistry.summary());
        b.append("\nmodules: ").append(Modules.active().stream().map(SplintModule::id).toList());
        b.append('\n').append(Counters.summary());
        for (SplintModule m : Modules.active()) {
            String s = m.status();
            if (!s.isEmpty()) b.append('\n').append(s);
        }
        return b.toString();
    }

    static String modules() {
        StringBuilder b = new StringBuilder("side " + Modules.side().name().toLowerCase(Locale.ROOT));
        for (SplintModule m : Modules.active()) {
            b.append('\n').append(m.id()).append(": ").append(m.description()).append(" (")
                    .append(m.patches().size() + m.redirects().size()).append(" patches, ")
                    .append(m.commands().size()).append(" commands)");
        }
        if (!Modules.skipped().isEmpty()) b.append("\nskipped: ").append(Modules.skipped());
        return b.toString();
    }

    static String problems(Args a) {
        int n = a.more() ? a.integer("N") : 10;
        Map<String, Long> kinds = Incidents.byKind();
        if (kinds.isEmpty()) return "No problems handled since the server started.";
        StringBuilder b = new StringBuilder("Problems handled since start: ").append(kinds);
        b.append("\nWorst map cells:");
        for (var e : Incidents.topCells(5)) {
            String[] xy = e.getKey().split(",");
            List<String> who = World.playersInCell(Integer.parseInt(xy[0]), Integer.parseInt(xy[1]));
            b.append("\n  cell ").append(e.getKey()).append(": ").append(e.getValue()).append(" (apop_")
                    .append(xy[0]).append('_').append(xy[1]).append(".bin) players there now: ")
                    .append(who.isEmpty() ? "none" : String.join(",", who));
        }
        b.append("\nLatest ").append(n).append(':');
        for (Incidents.Incident i : Incidents.recent(n)) b.append("\n  ").append(i.line());
        return b.toString();
    }

    static String players(Args a, CommandContext ctx) {
        AreaTerms.Parsed area = a.more() ? AreaTerms.parse(a.lower(), a, ctx) : null;
        if (a.more()) throw new Usage("players takes one AREA: " + AreaTerms.HELP);
        List<IsoPlayer> online = World.players();
        if (online.isEmpty()) return "No players online.";
        StringBuilder b = new StringBuilder(online.size() + " players online");
        if (area != null) b.append(", showing those ").append(area.label());
        int shown = 0;
        for (IsoPlayer p : online) {
            int px = (int) p.getX(), py = (int) p.getY(), pz = (int) p.getZ();
            if (area != null && !area.area().contains(px, py, pz)) continue;
            if (shown++ >= PLAYER_LIST_LIMIT) continue;
            b.append('\n').append(p.getUsername()).append(" at ").append(px).append(',').append(py).append(',').append(pz)
                    .append(" cell ").append(World.cell(px)).append(',').append(World.cell(py));
            for (SplintModule m : Modules.active()) {
                String about = m.aboutPlayer(p);
                if (!about.isEmpty()) b.append(' ').append(m.id()).append(": ").append(about);
            }
        }
        if (shown > PLAYER_LIST_LIMIT) b.append("\n... ").append(shown - PLAYER_LIST_LIMIT).append(" more");
        return b.toString();
    }
}
