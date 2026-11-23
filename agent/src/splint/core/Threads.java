package splint.core;

import java.util.concurrent.ConcurrentLinkedQueue;
import zombie.network.GameServer;

/**
 * Which thread is the game's main thread, and a queue for work that must run there. The
 * dedicated server loads chunks (and the animals in hutches on them) on a loader thread; game
 * state that the main thread owns is only touched through {@link #onMain}.
 */
public final class Threads {
    private static final ConcurrentLinkedQueue<Runnable> MAIN = new ConcurrentLinkedQueue<>();
    private static volatile Thread clientMain;

    private Threads() {}

    public static boolean isMain() {
        Thread t = Thread.currentThread();
        if (GameServer.server) return t == GameServer.mainThread;
        return t == clientMain;
    }

    /** Runs {@code r} now when on the main thread, otherwise at the start of the next world update. */
    public static void onMain(Runnable r) {
        if (isMain()) r.run();
        else MAIN.add(r);
    }

    /** Called from the heartbeat, on the main thread. */
    static void drain() {
        if (clientMain == null && !GameServer.server) clientMain = Thread.currentThread();
        for (Runnable r; (r = MAIN.poll()) != null; ) {
            try {
                r.run();
            } catch (Throwable t) {
                splint.core.log.Log.problem("main-queue", "deferred task threw; contained", t);
            }
        }
    }
}
