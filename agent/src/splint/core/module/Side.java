package splint.core.module;

/**
 * Which game process a module's patches belong to. The agent cannot always tell the server
 * from the client at start-up (the native launcher hides the main class), so {@link #detect}
 * falls back to {@link #UNKNOWN}, in which case every module loads and hooks that are
 * side-specific check {@code GameServer.server} / {@code GameClient.client} at run time.
 */
public enum Side {
    SERVER, CLIENT, BOTH, UNKNOWN;

    /** {@code -Dsplint.side=server|client} wins; otherwise the java command line, if visible. */
    public static Side detect() {
        String forced = System.getProperty("splint.side", "").trim().toLowerCase(java.util.Locale.ROOT);
        if (forced.equals("server")) return SERVER;
        if (forced.equals("client")) return CLIENT;
        String cmd = System.getProperty("sun.java.command", "");
        if (cmd.contains("zombie.network.GameServer") || cmd.contains("zombie/network/GameServer")) return SERVER;
        if (cmd.contains("zombie.gameStates.MainScreenState") || cmd.contains("zombie/gameStates/MainScreenState")) return CLIENT;
        return UNKNOWN;
    }

    /** Whether a module declared for {@code wanted} runs in a process of this side. */
    public boolean runs(Side wanted) {
        return this == UNKNOWN || wanted == BOTH || wanted == this;
    }
}
