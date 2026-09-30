package local.archi.ai;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Runs the locally installed Claude Code CLI (the user's own login/subscription)
 * in headless stream-json mode and relays its events to a listener.
 * One process holds one conversation; after a restart the conversation continues via --resume.
 */
final class ClaudeProcess implements ChatBackend {

    @Override
    public String label() {
        return "Claude Code (подписка)";
    }

    interface Listener {
        void onText(String text);
        void onTool(String name, String detail);
        void onToolError(String text);
        void onSystem(String text);
        void onTurnDone(boolean error, String info);
        void onExit(int code, String stderrTail);
    }

    private final Listener listener;
    private Process proc;
    private BufferedWriter stdin;
    private volatile String sessionId;
    private volatile boolean streamedText;
    private final StringBuilder stderrTail = new StringBuilder();

    ClaudeProcess(Listener listener) {
        this.listener = listener;
    }

    @Override public synchronized boolean isRunning() {
        return proc != null && proc.isAlive();
    }

    @Override public String sessionId() {
        return sessionId;
    }

    /** Forget the conversation: the next start opens a new session. */
    @Override public synchronized void reset() {
        stop();
        sessionId = null;
    }

    @Override public synchronized void start(File cwd) throws IOException {
        if (isRunning()) return;
        List<String> cmd = new ArrayList<>();
        cmd.add(ClaudeEnv.claudeExecutable());
        cmd.add("-p");
        cmd.add("--input-format");
        cmd.add("stream-json");
        cmd.add("--output-format");
        cmd.add("stream-json");
        cmd.add("--verbose");
        cmd.add("--include-partial-messages");
        cmd.add("--mcp-config");
        cmd.add(ClaudeEnv.mcpConfig().getAbsolutePath());
        cmd.add("--strict-mcp-config");
        cmd.add("--allowedTools");
        cmd.add(ClaudeEnv.allowedTools() + ",Bash");
        cmd.add("--append-system-prompt");
        cmd.add(ClaudeEnv.systemPrompt());
        if (sessionId != null) {
            cmd.add("--resume");
            cmd.add(sessionId);
        }
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(cwd);
        proc = pb.start();
        stdin = new BufferedWriter(new OutputStreamWriter(proc.getOutputStream(), StandardCharsets.UTF_8));
        synchronized (stderrTail) { stderrTail.setLength(0); }
        final Process p = proc;
        startThread("claude-stdout", () -> readStdout(p));
        startThread("claude-stderr", () -> readStderr(p));
        startThread("claude-exit", () -> {
            try {
                int code = p.waitFor();
                String tail;
                synchronized (stderrTail) { tail = stderrTail.toString(); }
                listener.onExit(code, tail);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    @Override public synchronized void send(String text, List<Attachment> files) throws IOException {
        if (files != null && !files.isEmpty()) {
            StringBuilder b = new StringBuilder(text).append("\n\nВложенные файлы (прочитай их с диска и проанализируй сам):");
            for (Attachment a : files) b.append("\n- ").append(a.file().getAbsolutePath());
            text = b.toString();
        }
        if (!isRunning()) throw new IOException("Claude Code не запущен");
        String line = "{\"type\":\"user\",\"message\":{\"role\":\"user\",\"content\":" + Json.quote(text) + "}}";
        stdin.write(line);
        stdin.write('\n');
        stdin.flush();
    }

    @Override public synchronized void stop() {
        if (proc != null) {
            proc.descendants().forEach(ProcessHandle::destroy);
            proc.destroy();
            proc = null;
        }
    }

    private static void startThread(String name, Runnable r) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        t.start();
    }

    private void readStderr(Process p) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                synchronized (stderrTail) {
                    stderrTail.append(line).append('\n');
                    if (stderrTail.length() > 4000) stderrTail.delete(0, stderrTail.length() - 4000);
                }
            }
        } catch (IOException ignored) {
            // process ended
        }
    }

    private void readStdout(Process p) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isBlank()) continue;
                try {
                    handle(Json.parse(line));
                } catch (RuntimeException ex) {
                    listener.onSystem("Не удалось разобрать ответ: " + ex.getMessage());
                }
            }
        } catch (IOException ignored) {
            // process ended
        }
    }

    private void handle(Object ev) {
        String type = Json.str(ev, "type");
        if (type == null) return;
        switch (type) {
            case "system" -> {
                if ("init".equals(Json.str(ev, "subtype"))) {
                    sessionId = Json.str(ev, "session_id");
                    StringBuilder b = new StringBuilder("Claude Code подключён");
                    for (Object s : Json.list(ev, "mcp_servers")) {
                        b.append(" · MCP ").append(Json.str(s, "name")).append(": ").append(Json.str(s, "status"));
                    }
                    listener.onSystem(b.toString());
                }
            }
            case "stream_event" -> {
                Map<String, Object> e = Json.obj(ev, "event");
                if (e != null && "content_block_delta".equals(Json.str(e, "type"))) {
                    Map<String, Object> d = Json.obj(e, "delta");
                    if (d != null && "text_delta".equals(Json.str(d, "type"))) {
                        String t = Json.str(d, "text");
                        if (t != null) {
                            streamedText = true;
                            listener.onText(t);
                        }
                    }
                }
            }
            case "assistant" -> {
                Map<String, Object> msg = Json.obj(ev, "message");
                for (Object block : Json.list(msg, "content")) {
                    String bt = Json.str(block, "type");
                    if ("text".equals(bt) && !streamedText) {
                        listener.onText(Json.str(block, "text"));
                    } else if ("tool_use".equals(bt)) {
                        listener.onTool(Json.str(block, "name"), summarize(Json.obj(block, "input")));
                    }
                }
                streamedText = false;
            }
            case "user" -> {
                Map<String, Object> msg = Json.obj(ev, "message");
                for (Object block : Json.list(msg, "content")) {
                    if ("tool_result".equals(Json.str(block, "type")) && block instanceof Map<?, ?> m
                            && Boolean.TRUE.equals(m.get("is_error"))) {
                        listener.onToolError(shorten(String.valueOf(m.get("content")), 300));
                    }
                }
            }
            case "result" -> {
                String sid = Json.str(ev, "session_id");
                if (sid != null) sessionId = sid;
                boolean err = ev instanceof Map<?, ?> m && Boolean.TRUE.equals(m.get("is_error"));
                String info = "";
                if (ev instanceof Map<?, ?> m && m.get("duration_ms") instanceof Double ms) {
                    info = String.format("%.1f c", ms / 1000.0);
                }
                if (err) {
                    String res = Json.str(ev, "result");
                    if (res != null) info = info + " · " + shorten(res, 300);
                }
                listener.onTurnDone(err, info);
            }
            default -> { /* ignore other event types */ }
        }
    }

    private static String summarize(Map<String, Object> input) {
        if (input == null || input.isEmpty()) return "";
        if (input.get("operations") instanceof List<?> ops) return ops.size() + " операций";
        StringBuilder b = new StringBuilder();
        for (Map.Entry<String, Object> e : input.entrySet()) {
            Object v = e.getValue();
            if (v instanceof String || v instanceof Double || v instanceof Boolean) {
                if (b.length() > 0) b.append(", ");
                b.append(e.getKey()).append('=').append(v);
            }
        }
        return shorten(b.toString(), 140);
    }

    private static String shorten(String s, int max) {
        if (s == null) return "";
        s = s.replace('\n', ' ');
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
