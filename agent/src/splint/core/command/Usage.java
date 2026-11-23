package splint.core.command;

/** Bad command input: the message is shown to the admin as the reply. */
public final class Usage extends RuntimeException {
    public Usage(String message) {
        super(message, null, false, false);
    }
}
