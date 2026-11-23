package splint.core.command;

import splint.core.log.Log;

/** Who ran a command and how; replies go only to them. */
public final class CommandContext {
    private final String username;
    private final boolean console;

    CommandContext(String username, boolean console) {
        this.username = username;
        this.console = console;
    }

    /** The in-game admin's name, or null from the console / RCON. */
    public String player() { return console ? null : username; }

    /** For audit lines: the admin's name, or "console". */
    public String who() { return console ? "console" : username; }

    public boolean console() { return console; }

    /**
     * World changes must run on the main thread. Console, RCON and chat commands do; the
     * Discord bridge calls in from its own thread, so it only gets read-only commands.
     */
    public void requireMainThread() {
        Thread main = zombie.network.GameServer.mainThread;
        if (main != null && Thread.currentThread() != main) {
            throw new Usage("This command changes the world; run it from the server console, RCON or in-game chat.");
        }
    }

    /** Records an admin action in the Splint log. */
    public void audit(String what) {
        Log.info("admin " + who() + ": " + what);
    }
}
