package local.archi.ai;

import java.io.File;
import java.io.IOException;

/** A conversation engine behind the chat panel: Claude Code (subscription) or a direct API connection. */
interface ChatBackend {
    /** Human-readable name for the panel, e.g. «Claude Code (подписка)» or «API · Anthropic · claude-opus-5». */
    String label();

    boolean isRunning();

    void start(File cwd) throws IOException;

    /** Sends one user turn; events arrive through {@link ClaudeProcess.Listener}. */
    void send(String text, java.util.List<Attachment> files) throws IOException;

    /** Interrupts the current turn; the conversation continues with the next message. */
    void stop();

    /** Forgets the conversation. */
    void reset();

    /** The panel is closing: stop the turn and release helper processes. */
    default void close() {
        stop();
    }

    String sessionId();
}
