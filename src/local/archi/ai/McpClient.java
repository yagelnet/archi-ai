package local.archi.ai;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** Minimal MCP client (Streamable HTTP, JSON-RPC 2.0) for the Archi MCP server. */
final class McpClient implements McpTools {

    record Tool(String name, String description, Map<String, Object> inputSchema) {}

    record CallResult(String text, boolean error) {}

    private static final String PROTOCOL_VERSION = "2025-06-18";

    private final String url;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final AtomicInteger ids = new AtomicInteger();
    private String sessionId;
    private boolean initialized;

    McpClient(String url) {
        this.url = url;
    }

    synchronized void initialize() throws IOException {
        if (initialized) return;
        Object res = rpc("initialize", Json.map(
                "protocolVersion", PROTOCOL_VERSION,
                "capabilities", Json.map(),
                "clientInfo", Json.map("name", "archi-claude-plugin", "version", "1.2.0")));
        if (res == null) throw new IOException("MCP-сервер не ответил на initialize");
        notifyServer("notifications/initialized");
        initialized = true;
    }

    @Override
    public synchronized List<Tool> listTools() throws IOException {
        initialize();
        List<Tool> tools = new ArrayList<>();
        String cursor = null;
        do {
            Object res = rpc("tools/list", cursor == null ? Json.map() : Json.map("cursor", cursor));
            addTools(res, tools);
            cursor = Json.str(res, "nextCursor");
        } while (cursor != null && !cursor.isEmpty());
        return tools;
    }

    @Override
    public CallResult callTool(String name, Map<String, Object> args) throws IOException {
        Object res;
        synchronized (this) {
            initialize();
            res = rpc("tools/call", Json.map("name", name, "arguments", args == null ? Json.map() : args));
        }
        return toResult(res);
    }

    /** Appends the tools of one tools/list page. */
    static void addTools(Object res, List<Tool> tools) {
        for (Object t : Json.list(res, "tools")) {
            Map<String, Object> schema = Json.obj(t, "inputSchema");
            if (schema == null) schema = Json.map("type", "object", "properties", Json.map());
            String desc = Json.str(t, "description");
            tools.add(new Tool(Json.str(t, "name"), desc == null ? "" : desc, schema));
        }
    }

    /** Text of a tools/call result. */
    static CallResult toResult(Object res) {
        StringBuilder b = new StringBuilder();
        for (Object c : Json.list(res, "content")) {
            String type = Json.str(c, "type");
            if ("text".equals(type)) {
                if (b.length() > 0) b.append('\n');
                b.append(Json.str(c, "text"));
            } else if (type != null) {
                if (b.length() > 0) b.append('\n');
                b.append("[").append(type).append(" — не передаётся в этом режиме]");
            }
        }
        boolean err = res instanceof Map<?, ?> m && Boolean.TRUE.equals(m.get("isError"));
        return new CallResult(b.toString(), err);
    }

    private void notifyServer(String method) throws IOException {
        post(Json.write(Json.map("jsonrpc", "2.0", "method", method)));
    }

    private Object rpc(String method, Map<String, Object> params) throws IOException {
        int id = ids.incrementAndGet();
        String body = Json.write(Json.map("jsonrpc", "2.0", "id", id, "method", method, "params", params));
        HttpResponse<String> resp = post(body);
        Object msg = findResponse(resp, id);
        if (msg == null) throw new IOException("Пустой ответ MCP на " + method + " (HTTP " + resp.statusCode() + ")");
        Map<String, Object> err = Json.obj(msg, "error");
        if (err != null) throw new IOException("MCP " + method + ": " + Json.str(err, "message"));
        return ((Map<?, ?>) msg).get("result");
    }

    private HttpResponse<String> post(String body) throws IOException {
        HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMinutes(5))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (sessionId != null) rb.header("Mcp-Session-Id", sessionId);
        if (initialized) rb.header("MCP-Protocol-Version", PROTOCOL_VERSION);
        HttpResponse<String> resp;
        try {
            resp = http.send(rb.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Запрос к MCP прерван", e);
        } catch (IOException e) {
            if (!url.equals(AiSettings.mcpUrl())) throw new IOException("MCP-сервер недоступен по адресу " + url, e);
            throw new IOException("MCP-сервер Archi недоступен по адресу " + url
                    + ". Проверьте, что Archi запущен и MCP-сервер стартовал (меню MCP Server).", e);
        }
        resp.headers().firstValue("Mcp-Session-Id").ifPresent(s -> sessionId = s);
        if (resp.statusCode() == 404 && sessionId != null) {
            // session expired: start over on the next call
            sessionId = null;
            initialized = false;
        }
        if (resp.statusCode() >= 400) {
            throw new IOException("MCP HTTP " + resp.statusCode() + ": " + shorten(resp.body()));
        }
        return resp;
    }

    /** The response body is plain JSON or an SSE stream of JSON-RPC messages. */
    private static Object findResponse(HttpResponse<String> resp, int id) {
        String body = resp.body();
        if (body == null || body.isBlank()) return null;
        String ct = resp.headers().firstValue("Content-Type").orElse("");
        if (!ct.contains("text/event-stream")) return Json.parse(body);
        StringBuilder data = new StringBuilder();
        for (String line : body.split("\\r?\\n")) {
            if (line.startsWith("data:")) {
                data.append(line.substring(5).trim());
            } else if (line.isEmpty() && data.length() > 0) {
                Object msg = Json.parse(data.toString());
                data.setLength(0);
                if (msg instanceof Map<?, ?> m && m.get("id") instanceof Double d && d.intValue() == id) return msg;
            }
        }
        if (data.length() > 0) {
            Object msg = Json.parse(data.toString());
            if (msg instanceof Map<?, ?> m && m.get("id") instanceof Double d && d.intValue() == id) return msg;
        }
        return null;
    }

    private static String shorten(String s) {
        if (s == null) return "";
        return s.length() <= 300 ? s : s.substring(0, 300) + "…";
    }
}
