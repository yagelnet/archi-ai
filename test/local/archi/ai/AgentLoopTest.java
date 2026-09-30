package local.archi.ai;

import java.io.File;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpServer;

/** Runs ApiAgent against a fake LLM server (both protocols) and the real Archi MCP server. */
public class AgentLoopTest {

    public static void main(String[] a) throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpServer srv = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        srv.createContext("/", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (ex.getRequestURI().getPath().endsWith("/v1/files")) {
                System.out.println("  UPLOAD multipart bytes=" + body.length() + " hasFileField=" + body.contains("name=\"file\""));
                byte[] up = "{\"id\":\"file_test123\"}".getBytes(StandardCharsets.UTF_8);
                ex.sendResponseHeaders(200, up.length); try (OutputStream os = ex.getResponseBody()) { os.write(up); } return;
            }
            String flags = " container_upload=" + body.contains("container_upload") + " codeTool=" + body.contains("code_execution_20260521") + " fileRef=" + body.contains("file_test123") + " openaiFile=" + body.contains("\"type\":\"file\"");
            Object req = Json.parse(body);
            List<?> msgs = Json.list(req, "messages");
            boolean sawToolResult = body.contains("tool_result") || body.contains("\"role\":\"tool\"");
            int tools = Json.list(req, "tools").size();
            calls.incrementAndGet();
            String resp;
            int results = body.split("tool_use_id", -1).length - 1;
            if (ex.getRequestURI().getPath().endsWith("/v1/messages") && results == 0) {
                System.out.println("  step0 tools=" + tools + " bodyChars=" + body.length());
                resp = Json.write(Json.map("content", List.of(Json.map("type", "tool_use", "id", "toolu_0", "name", "load_tools",
                        "input", Json.map("names", List.of("get-model-info", "search-elements", "no-such-tool")))),
                    "stop_reason", "tool_use", "usage", Json.map("input_tokens", 10, "output_tokens", 5)));
            } else if (ex.getRequestURI().getPath().endsWith("/v1/messages")) {
                if (results == 1) System.out.println("  step1 tools=" + tools + " loaderResult=" + body.substring(body.indexOf("Загружены"), body.indexOf("Загружены") + 90));
                sawToolResult = results >= 2;
                resp = !sawToolResult
                    ? Json.write(Json.map("content", List.of(Json.map("type", "text", "text", "Смотрю модель. "),
                            Json.map("type", "tool_use", "id", "toolu_1", "name", "get-model-info", "input", Json.map())),
                        "stop_reason", "tool_use", "usage", Json.map("input_tokens", 100, "output_tokens", 10)))
                    : Json.write(Json.map("content", List.of(Json.map("type", "text", "text", "Готово (anthropic)" + flags + ", tools=" + tools
                            + ", bodyChars=" + body.length())),
                        "stop_reason", "end_turn", "usage", Json.map("input_tokens", 120, "output_tokens", 12)));
            } else {
                resp = !sawToolResult
                    ? Json.write(Json.map("choices", List.of(Json.map("finish_reason", "tool_calls", "message", Json.map(
                        "role", "assistant", "content", null, "tool_calls", List.of(Json.map("id", "call_1", "type", "function",
                            "function", Json.map("name", "get-model-info", "arguments", "{}"))))))))
                    : Json.write(Json.map("choices", List.of(Json.map("finish_reason", "stop",
                        "message", Json.map("role", "assistant", "content", "Готово (openai)" + flags + ", msgs=" + msgs.size() + ", tools=" + tools
                            + ", bodyChars=" + body.length())))));
            }
            byte[] out = resp.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, out.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(out); }
        });
        srv.start();
        String base = "http://127.0.0.1:" + srv.getAddress().getPort();

        for (String provider : new String[] {"custom", "anthropic"}) {
            AiSettings.set("mode", "api");
            AiSettings.set("api.provider", provider);
            AiSettings.set("api.baseUrl." + provider, base);
            AiSettings.set("api.model." + provider, "fake-model");
            AiSettings.set("api.toolset", provider.equals("custom") ? "core" : "all");
            CountDownLatch done = new CountDownLatch(1);
            ClaudeProcess.Listener l = new ClaudeProcess.Listener() {
                public void onText(String t) { System.out.println("  TEXT: " + t); }
                public void onTool(String n, String d) { System.out.println("  TOOL: " + n + " " + d); }
                public void onToolError(String t) { System.out.println("  TOOL-ERR: " + t); }
                public void onSystem(String t) { System.out.println("  SYS: " + t); }
                public void onTurnDone(boolean e, String i) { System.out.println("  DONE err=" + e + " " + i); done.countDown(); }
                public void onExit(int c, String s) { }
            };
            System.out.println("== " + provider);
            ApiAgent agent = new ApiAgent(l);
            try {
                agent.start(new File("."));
                File sample = File.createTempFile("archi-ai-test", ".xlsx");
                sample.deleteOnExit();
                agent.send("Проанализируй таблицу", List.of(Attachment.of(sample)));
                done.await(60, TimeUnit.SECONDS);
            } catch (Exception e) {
                System.out.println("  EXCEPTION: " + e.getMessage());
            }
        }
        System.out.println("fake LLM calls: " + calls.get());
        srv.stop(0);
    }
}
