package local.archi.ai;

import java.io.File;
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
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import local.archi.ai.AiSettings.Protocol;
import local.archi.ai.AiSettings.Provider;

/**
 * Direct API mode: the plugin runs the agent loop itself. It lists the Archi MCP server's tools,
 * offers them to the model, executes the tool calls the model makes and feeds results back until
 * the model answers. Two wire protocols: Anthropic Messages API and OpenAI-compatible Chat Completions.
 */
final class ApiAgent implements ChatBackend {


    /** Copy of a JSON schema with every nested "description" cut to {@code max} characters (0 = unchanged). */
    @SuppressWarnings("unchecked")
    static Object cutDescriptions(Object o, int max) {
        if (max <= 0) return o;
        if (o instanceof Map<?, ?> m) {
            Map<String, Object> copy = new java.util.LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                String k = String.valueOf(e.getKey());
                Object v = e.getValue();
                copy.put(k, "description".equals(k) && v instanceof String s ? cut(s, max) : cutDescriptions(v, max));
            }
            return copy;
        }
        if (o instanceof List<?> l) {
            List<Object> copy = new ArrayList<>();
            for (Object v : l) copy.add(cutDescriptions(v, max));
            return copy;
        }
        return o;
    }

    private static Object schema(McpClient.Tool t) {
        int max = AiSettings.toolDescMax();
        return cutDescriptions(t.inputSchema(), max <= 0 ? 0 : Math.max(120, max / 4));
    }

    // ---- lazy tool loading ----
    // The full definitions of the 77 Archi tools weigh ~55k tokens and would be resent on every step.
    // Instead the system prompt carries a one-line catalog, and the model loads full schemas of the
    // tools it needs through LOADER; loaded tools stay available for the rest of the conversation.

    static final String LOADER = "load_tools";

    private static final Map<String, Object> LOADER_SCHEMA = Json.map("type", "object",
            "properties", Json.map("names", Json.map("type", "array", "items", Json.map("type", "string"),
                    "description", "Имена инструментов из каталога")),
            "required", List.of("names"));

    private static final String LOADER_DESC = "Загружает полные описания и схемы параметров инструментов из каталога "
            + "в системном промпте. Вызови перед первым использованием инструмента; можно загрузить сразу несколько.";

    private String catalog() {
        StringBuilder b = new StringBuilder("\n\nИнструменты. Ниже только каталог: перед первым вызовом инструмента загрузи его схему через ")
                .append(LOADER).append("(names), затем вызывай сам инструмент. Загруженные инструменты остаются доступны до конца диалога.\n");
        String server = null;
        for (McpClient.Tool t : tools) {
            String s = serverOf.getOrDefault(t.name(), ARCHI);
            if (!s.equals(server)) {
                server = s;
                b.append(ARCHI.equals(s) ? "\nArchi (модель ArchiMate):\n" : "\nMCP-сервер " + s + ":\n");
            }
            b.append("- ").append(t.name()).append(" — ").append(firstSentence(t.description())).append('\n');
        }
        return b.toString();
    }

    private static String firstSentence(String s) {
        s = s == null ? "" : s.replaceAll("\\s+", " ").trim();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^(.{20,}?[.!?])(\\s|$)").matcher(s);
        return cut(m.find() ? m.group(1) : s, 160);
    }

    /** Handles a LOADER call: marks the requested tools as loaded and reports the result. */
    private McpClient.CallResult loadTools(Map<String, Object> input) {
        List<String> ok = new ArrayList<>();
        List<String> unknown = new ArrayList<>();
        if (input.get("names") instanceof List<?> names) {
            for (Object n : names) {
                String name = String.valueOf(n).replaceFirst("^mcp__[A-Za-z0-9-]+__", "");
                if (byName.containsKey(name)) {
                    loaded.add(name);
                    ok.add(name);
                } else {
                    unknown.add(name);
                }
            }
        }
        listener.onTool("Загрузка инструментов", String.join(", ", ok));
        StringBuilder r = new StringBuilder();
        if (!ok.isEmpty()) r.append("Загружены: ").append(String.join(", ", ok)).append(". Теперь их можно вызывать.");
        if (!unknown.isEmpty()) r.append(r.length() > 0 ? " " : "").append("Нет в каталоге: ").append(String.join(", ", unknown)).append('.');
        if (r.length() == 0) r.append("Не указаны имена инструментов (параметр names).");
        return new McpClient.CallResult(r.toString(), ok.isEmpty());
    }

    private List<Object> anthropicTools() {
        List<Object> defs = new ArrayList<>();
        if (codeExec) defs.add(Json.map("type", "code_execution_20260521", "name", "code_execution"));
        defs.add(Json.map("name", LOADER, "description", LOADER_DESC, "input_schema", LOADER_SCHEMA));
        int max = AiSettings.toolDescMax();
        for (String n : loaded) {
            McpClient.Tool t = byName.get(n);
            defs.add(Json.map("name", t.name(), "description", cut(t.description(), max), "input_schema", schema(t)));
        }
        // cache the stable prefix: it only changes when a tool gets loaded
        @SuppressWarnings("unchecked")
        Map<String, Object> last = (Map<String, Object>) defs.get(defs.size() - 1);
        last.put("cache_control", Json.map("type", "ephemeral"));
        return defs;
    }

    private List<Object> openAiTools() {
        List<Object> defs = new ArrayList<>();
        defs.add(Json.map("type", "function", "function", Json.map("name", LOADER, "description", LOADER_DESC, "parameters", LOADER_SCHEMA)));
        int max = AiSettings.toolDescMax();
        for (String n : loaded) {
            McpClient.Tool t = byName.get(n);
            defs.add(Json.map("type", "function", "function",
                    Json.map("name", t.name(), "description", cut(t.description(), max), "parameters", schema(t))));
        }
        return defs;
    }

    private static final int MAX_STEPS = 40;
    private static final int MAX_RETRIES = 2;

    private final ClaudeProcess.Listener listener;
    private final HttpClient http = HttpClient.newBuilder().sslContext(Tls.context()).connectTimeout(Duration.ofSeconds(20)).build();

    private Provider provider;
    private String baseUrl;
    private String model;
    private String apiKey;
    private int maxTokens;
    private static final String ARCHI = "archi";

    private McpClient mcp;
    /** Connections to the additional MCP servers (Jira, Confluence…), by server name. */
    private final Map<String, McpTools> extra = new java.util.LinkedHashMap<>();
    /** Server name of every non-Archi tool. */
    private final Map<String, String> serverOf = new java.util.HashMap<>();
    private List<McpClient.Tool> tools = List.of();
    private final Map<String, McpClient.Tool> byName = new java.util.HashMap<>();
    private final java.util.Set<String> loaded = new java.util.LinkedHashSet<>();
    private final List<Object> history = new ArrayList<>();
    private String system;
    private String sessionId;
    private boolean running;

    private volatile boolean cancelled;
    private volatile CompletableFuture<HttpResponse<String>> inFlight;
    private Thread worker;

    ApiAgent(ClaudeProcess.Listener listener) {
        this.listener = listener;
    }

    @Override
    public String label() {
        Provider p = provider != null ? provider : AiSettings.provider();
        String m = model != null ? model : AiSettings.model();
        return "API · " + p.label() + (m.isEmpty() ? "" : " · " + m);
    }

    @Override
    public synchronized boolean isRunning() {
        return running;
    }

    @Override
    public String sessionId() {
        return sessionId;
    }

    @Override
    public synchronized void start(File cwd) throws IOException {
        if (running) return;
        provider = AiSettings.provider();
        baseUrl = trimSlash(AiSettings.baseUrl());
        model = AiSettings.model().trim();
        apiKey = AiSettings.apiKey(provider);
        maxTokens = Math.max(1024, AiSettings.maxTokens());
        if (baseUrl.isEmpty()) throw new IOException("Не задан адрес API (вкладка «Подключение»)");
        if (model.isEmpty()) throw new IOException("Не задана модель (вкладка «Подключение»)");
        if (provider.keyRequired() && apiKey.isEmpty()) {
            throw new IOException("Нет API-ключа для " + provider.label() + ". Введите его на вкладке «Подключение»"
                    + (provider.envKey().isEmpty() ? "" : " или задайте переменную " + provider.envKey()));
        }
        mcp = new McpClient(AiSettings.mcpUrl());
        List<McpClient.Tool> all = new ArrayList<>(mcp.listTools());
        byName.clear();
        serverOf.clear();
        for (McpClient.Tool t : all) byName.put(t.name(), t);
        StringBuilder info = new StringBuilder(" · инструментов Archi: " + all.size());
        closeExtra();
        for (Map.Entry<String, String> s : ClaudeEnv.extraServers().entrySet()) {
            try {
                McpTools c = new McpClient(s.getValue());
                extra.put(s.getKey(), c);
                int n = 0;
                for (McpClient.Tool t : c.listTools()) {
                    if (byName.containsKey(t.name())) continue; // name clash: Archi and earlier servers win
                    byName.put(t.name(), t);
                    serverOf.put(t.name(), s.getKey());
                    all.add(t);
                    n++;
                }
                info.append(" · ").append(s.getKey()).append(": ").append(n);
            } catch (IOException e) {
                listener.onSystem("MCP-сервер " + s.getKey() + " недоступен: " + e.getMessage());
            }
        }
        tools = all;
        loaded.retainAll(byName.keySet());
        system = ClaudeEnv.systemPrompt().replace("(инструменты mcp__archi__*)", "(инструменты Archi)") + catalog();
        if (sessionId == null) sessionId = "api-" + UUID.randomUUID();
        running = true;
        listener.onSystem("Подключено: " + label() + info);
    }

    @Override
    public void close() {
        stop();
        closeExtra();
    }

    private synchronized void closeExtra() {
        for (McpTools c : extra.values()) c.close();
        extra.clear();
    }

    @Override
    public synchronized void send(String text, List<Attachment> files) throws IOException {
        if (!running) throw new IOException("Подключение не установлено");
        if (worker != null && worker.isAlive()) throw new IOException("Предыдущий ответ ещё не завершён");
        cancelled = false;
        worker = new Thread(() -> runTurn(text, files == null ? List.of() : List.copyOf(files)), "archi-api-agent");
        worker.setDaemon(true);
        worker.start();
    }

    @Override
    public void stop() {
        cancelled = true;
        CompletableFuture<HttpResponse<String>> f = inFlight;
        if (f != null) f.cancel(true);
    }

    @Override
    public synchronized void reset() {
        stop();
        history.clear();
        loaded.clear();
        codeExec = false;
        containerId = null;
        sessionId = null;
        running = false;
        closeExtra();
    }

    // ---- agent loop ----

    private void runTurn(String text, List<Attachment> files) {
        long t0 = System.currentTimeMillis();
        long[] usage = new long[2];
        try {
            synchronized (history) {
                history.add(Json.map("role", "user", "content", userContent(text, files)));
                if (provider.protocol() == Protocol.ANTHROPIC) anthropicLoop(usage);
                else openAiLoop(usage);
            }
            listener.onTurnDone(false, info(t0, usage));
        } catch (CancellationException e) {
            listener.onTurnDone(false, "прервано");
        } catch (IOException | RuntimeException e) {
            listener.onTurnDone(true, info(t0, usage) + " · " + e.getMessage());
        }
    }

    private static String info(long t0, long[] usage) {
        String s = String.format("%.1f c", (System.currentTimeMillis() - t0) / 1000.0);
        if (usage[0] + usage[1] > 0) s += " · токены: вход " + usage[0] + ", выход " + usage[1];
        return s;
    }

    // Anthropic Messages API

    @SuppressWarnings("unchecked")
    private void anthropicLoop(long[] usage) throws IOException {
        List<Object> sys = List.of(Json.map("type", "text", "text", system, "cache_control", Json.map("type", "ephemeral")));
        boolean fallback = model.equals("claude-opus-5") || model.equals("claude-fable-5-1");

        for (int step = 0; step < MAX_STEPS; step++) {
            checkCancelled();
            List<Object> toolDefs = anthropicTools();
            Map<String, Object> body = Json.map("model", model, "max_tokens", maxTokens, "system", sys, "tools", toolDefs, "messages", history);
            if (containerId != null) body.put("container", containerId);
            if (fallback) body.put("fallbacks", "default");
            HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(baseUrl + "/v1/messages"))
                    .header("content-type", "application/json")
                    .header("anthropic-version", "2023-06-01")
                    .header("x-api-key", apiKey);
            if (fallback) rb.header("anthropic-beta", "server-side-fallback-2026-07-01");
            Object resp = call(rb, Json.write(body));

            Map<String, Object> u = Json.obj(resp, "usage");
            if (u != null) {
                usage[0] += num(u.get("input_tokens")) + num(u.get("cache_read_input_tokens")) + num(u.get("cache_creation_input_tokens"));
                usage[1] += num(u.get("output_tokens"));
            }
            List<?> content = Json.list(resp, "content");
            history.add(Json.map("role", "assistant", "content", content));
            Map<String, Object> cont = Json.obj(resp, "container");
            if (cont != null && Json.str(cont, "id") != null) containerId = Json.str(cont, "id");
            for (Object block : content) {
                if ("server_tool_use".equals(Json.str(block, "type"))) {
                    Map<String, Object> in = Json.obj(block, "input");
                    String cmd = in == null ? "" : String.valueOf(in.getOrDefault("command", in.getOrDefault("path", "")));
                    listener.onTool("Анализ файла · " + Json.str(block, "name"), cut(cmd, 140));
                }
            }

            List<Map<?, ?>> calls = new ArrayList<>();
            for (Object block : content) {
                String type = Json.str(block, "type");
                if ("text".equals(type)) listener.onText(Json.str(block, "text"));
                else if ("tool_use".equals(type) && block instanceof Map<?, ?> m) calls.add(m);
            }
            String stop = Json.str(resp, "stop_reason");
            if ("refusal".equals(stop)) throw new IOException("модель отказалась выполнять запрос (refusal)");
            if ("max_tokens".equals(stop) && calls.isEmpty()) {
                listener.onSystem("Ответ обрезан по лимиту max_tokens (" + maxTokens + "). Попросите модель продолжить или разбить ответ на части.");
                return;
            }
            if ("pause_turn".equals(stop)) continue;
            if (calls.isEmpty()) return;

            List<Object> results = new ArrayList<>();
            for (Map<?, ?> c : calls) {
                String id = String.valueOf(c.get("id"));
                String name = String.valueOf(c.get("name"));
                Map<String, Object> input = c.get("input") instanceof Map<?, ?> in ? (Map<String, Object>) in : Json.map();
                McpClient.CallResult r = runTool(name, input);
                results.add(Json.map("type", "tool_result", "tool_use_id", id, "content", r.text(), "is_error", r.error()));
            }
            history.add(Json.map("role", "user", "content", results));
        }
        listener.onSystem("Достигнут предел шагов (" + MAX_STEPS + "). Напишите «продолжай», чтобы продолжить.");
    }

    // OpenAI-compatible Chat Completions

    @SuppressWarnings("unchecked")
    private void openAiLoop(long[] usage) throws IOException {
        for (int step = 0; step < MAX_STEPS; step++) {
            checkCancelled();
            List<Object> toolDefs = openAiTools();
            List<Object> msgs = new ArrayList<>();
            msgs.add(Json.map("role", "system", "content", system));
            msgs.addAll(history);
            Map<String, Object> body = Json.map("model", model, "messages", msgs, "tools", toolDefs,
                    "tool_choice", "auto", "max_tokens", maxTokens);
            HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(baseUrl + "/chat/completions"))
                    .header("Content-Type", "application/json");
            if (!apiKey.isEmpty()) rb.header("Authorization", "Bearer " + apiKey);
            Object resp = call(rb, Json.write(body));

            Map<String, Object> u = Json.obj(resp, "usage");
            if (u != null) {
                usage[0] += num(u.get("prompt_tokens"));
                usage[1] += num(u.get("completion_tokens"));
            }
            List<?> choices = Json.list(resp, "choices");
            if (choices.isEmpty()) throw new IOException("пустой ответ модели");
            Map<String, Object> msg = Json.obj(choices.get(0), "message");
            if (msg == null) throw new IOException("в ответе нет message");
            String textOut = Json.str(msg, "content");
            if (textOut != null && !textOut.isBlank()) listener.onText(textOut);
            List<?> calls = Json.list(msg, "tool_calls");

            Map<String, Object> assistant = Json.map("role", "assistant", "content", textOut == null ? "" : textOut);
            if (!calls.isEmpty()) assistant.put("tool_calls", calls);
            history.add(assistant);

            String finish = Json.str(choices.get(0), "finish_reason");
            if (calls.isEmpty()) {
                if ("length".equals(finish)) listener.onSystem("Ответ обрезан по лимиту max_tokens (" + maxTokens + ").");
                return;
            }
            for (Object c : calls) {
                Map<String, Object> fn = Json.obj(c, "function");
                String name = Json.str(fn, "name");
                String argStr = Json.str(fn, "arguments");
                Map<String, Object> args;
                try {
                    Object parsed = argStr == null || argStr.isBlank() ? Json.map() : Json.parse(argStr);
                    args = parsed instanceof Map<?, ?> pm ? (Map<String, Object>) pm : Json.map();
                } catch (RuntimeException e) {
                    history.add(Json.map("role", "tool", "tool_call_id", Json.str(c, "id"),
                            "content", "Ошибка: аргументы вызова не являются корректным JSON"));
                    continue;
                }
                McpClient.CallResult r = runTool(name, args);
                history.add(Json.map("role", "tool", "tool_call_id", Json.str(c, "id"),
                        "content", (r.error() ? "Ошибка: " : "") + r.text()));
            }
        }
        listener.onSystem("Достигнут предел шагов (" + MAX_STEPS + "). Напишите «продолжай», чтобы продолжить.");
    }

    /** Connection check from the settings tab: one short request without tools. Returns the model's reply. */
    static String ping() throws IOException {
        Provider p = AiSettings.provider();
        String base = trimSlash(AiSettings.baseUrl());
        String m = AiSettings.model().trim();
        String key = AiSettings.apiKey(p);
        if (base.isEmpty() || m.isEmpty()) throw new IOException("не заданы адрес API или модель");
        if (p.keyRequired() && key.isEmpty()) throw new IOException("нет API-ключа");
        HttpClient hc = HttpClient.newBuilder().sslContext(Tls.context()).connectTimeout(Duration.ofSeconds(15)).build();
        HttpRequest.Builder rb;
        String body;
        if (p.protocol() == Protocol.ANTHROPIC) {
            rb = HttpRequest.newBuilder(URI.create(base + "/v1/messages"))
                    .header("content-type", "application/json")
                    .header("anthropic-version", "2023-06-01")
                    .header("x-api-key", key);
            body = Json.write(Json.map("model", m, "max_tokens", 64,
                    "messages", List.of(Json.map("role", "user", "content", "Ответь одним словом: готов"))));
        } else {
            rb = HttpRequest.newBuilder(URI.create(base + "/chat/completions")).header("Content-Type", "application/json");
            if (!key.isEmpty()) rb.header("Authorization", "Bearer " + key);
            body = Json.write(Json.map("model", m, "max_tokens", 64,
                    "messages", List.of(Json.map("role", "user", "content", "Ответь одним словом: готов"))));
        }
        HttpResponse<String> resp;
        try {
            resp = hc.send(rb.timeout(Duration.ofSeconds(90)).POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("прервано");
        }
        if (resp.statusCode() >= 400) throw new IOException("HTTP " + resp.statusCode() + ": " + errorMessage(resp.body()));
        Object o = Json.parse(resp.body());
        if (p.protocol() == Protocol.ANTHROPIC) {
            for (Object b : Json.list(o, "content")) if ("text".equals(Json.str(b, "type"))) return Json.str(b, "text");
            return "(ответ без текста, stop_reason=" + Json.str(o, "stop_reason") + ")";
        }
        List<?> ch = Json.list(o, "choices");
        if (ch.isEmpty()) return "(пустой ответ)";
        Map<String, Object> msg = Json.obj(ch.get(0), "message");
        String content = Json.str(msg, "content");
        if (content != null && !content.isBlank()) return content;
        // Reasoning models (GLM, DeepSeek-R) may spend the whole short check budget on reasoning_content.
        String reasoning = Json.str(msg, "reasoning_content");
        if (reasoning != null && !reasoning.isBlank()) return "(модель отвечает; лимит проверки ушёл на рассуждение)";
        return "(ответ без текста, finish_reason=" + Json.str(ch.get(0), "finish_reason") + ")";
    }

    // ---- attachments: the plugin never parses files, it hands them to the model ----

    private boolean codeExec;
    private String containerId;
    private final Map<String, String> uploaded = new java.util.HashMap<>();

    /** User message content with attachments, in the wire format of the current protocol. */
    private Object userContent(String text, List<Attachment> files) throws IOException {
        if (files.isEmpty()) return text;
        List<Object> parts = new ArrayList<>();
        if (provider.protocol() == Protocol.ANTHROPIC) {
            for (Attachment a : files) {
                String id = uploadAnthropic(a);
                switch (a.kind()) {
                    case IMAGE -> parts.add(Json.map("type", "image", "source", Json.map("type", "file", "file_id", id)));
                    case PDF, TEXT -> parts.add(Json.map("type", "document", "source", Json.map("type", "file", "file_id", id), "title", a.name()));
                    case OTHER -> { parts.add(Json.map("type", "container_upload", "file_id", id)); codeExec = true; }
                }
                listener.onSystem("Файл «" + a.name() + "» (" + a.sizeLabel() + ") передан модели");
            }
            parts.add(Json.map("type", "text", "text", text));
            return parts;
        }
        // OpenAI-compatible: inline parts; whether a given type is accepted depends on the service
        parts.add(Json.map("type", "text", "text", text));
        for (Attachment a : files) {
            if (a.size() > 20L * 1024 * 1024) throw new IOException("файл «" + a.name() + "» больше 20 МБ");
            byte[] data = java.nio.file.Files.readAllBytes(a.file().toPath());
            switch (a.kind()) {
                case IMAGE -> parts.add(Json.map("type", "image_url", "image_url",
                        Json.map("url", "data:" + a.mime() + ";base64," + java.util.Base64.getEncoder().encodeToString(data))));
                case TEXT -> parts.add(Json.map("type", "text", "text", "Файл «" + a.name() + "»:\n" + decode(data)));
                case PDF, OTHER -> parts.add(Json.map("type", "file", "file", Json.map("filename", a.name(),
                        "file_data", "data:" + a.mime() + ";base64," + java.util.Base64.getEncoder().encodeToString(data))));
            }
            listener.onSystem("Файл «" + a.name() + "» (" + a.sizeLabel() + ") передан модели");
        }
        return parts;
    }

    private static String decode(byte[] data) {
        try {
            return java.nio.charset.StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(data)).toString();
        } catch (java.nio.charset.CharacterCodingException e) {
            return new String(data, java.nio.charset.Charset.forName("windows-1251"));
        }
    }

    /** Uploads a file to the Anthropic Files API (expires after a day) and returns its file_id. */
    private String uploadAnthropic(Attachment a) throws IOException {
        String cacheKey = a.file().getAbsolutePath() + "|" + a.file().lastModified() + "|" + a.size();
        String cached = uploaded.get(cacheKey);
        if (cached != null) return cached;
        String boundary = "----archi" + UUID.randomUUID();
        java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream();
        String safeName = a.name().replaceAll("[<>:\"|?*\\\\/\\p{Cntrl}]", "_");
        body.writeBytes(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"expires_in_seconds\"\r\n\r\n86400\r\n")
                .getBytes(StandardCharsets.UTF_8));
        body.writeBytes(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + safeName
                + "\"\r\nContent-Type: " + a.mime() + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.writeBytes(java.nio.file.Files.readAllBytes(a.file().toPath()));
        body.writeBytes(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/v1/files"))
                .timeout(Duration.ofMinutes(10))
                .header("x-api-key", apiKey)
                .header("anthropic-version", "2023-06-01")
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build();
        HttpResponse<String> resp;
        try {
            resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CancellationException();
        }
        if (resp.statusCode() >= 400) throw new IOException("загрузка «" + a.name() + "»: HTTP " + resp.statusCode() + ": " + errorMessage(resp.body()));
        String id = Json.str(Json.parse(resp.body()), "id");
        if (id == null) throw new IOException("загрузка «" + a.name() + "»: в ответе нет id");
        uploaded.put(cacheKey, id);
        return id;
    }

    // ---- helpers ----

    private McpClient.CallResult runTool(String name, Map<String, Object> input) {
        if (cancelled) return new McpClient.CallResult("Прервано пользователем", true);
        if (LOADER.equals(name)) return loadTools(input);
        // a tool called straight from the catalog still runs; its schema is loaded for the next steps
        if (byName.containsKey(name)) loaded.add(name);
        String server = serverOf.getOrDefault(name, ARCHI);
        listener.onTool("mcp__" + server + "__" + name, summarize(input));
        try {
            McpTools client = ARCHI.equals(server) ? mcp : extra.get(server);
            if (client == null) throw new IOException("MCP-сервер " + server + " не подключён");
            McpClient.CallResult r = client.callTool(name, input);
            if (r.error()) listener.onToolError(cut(r.text(), 300));
            return r;
        } catch (IOException e) {
            listener.onToolError(e.getMessage());
            return new McpClient.CallResult("Ошибка вызова инструмента: " + e.getMessage(), true);
        }
    }

    /** POST with retries on 429 / 5xx; returns the parsed JSON body. */
    private Object call(HttpRequest.Builder rb, String body) throws IOException {
        HttpRequest req = rb.timeout(Duration.ofMinutes(10))
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
        for (int attempt = 0; ; attempt++) {
            checkCancelled();
            HttpResponse<String> resp;
            try {
                inFlight = http.sendAsync(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                resp = inFlight.get();
            } catch (CancellationException e) {
                throw e;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new CancellationException();
            } catch (ExecutionException e) {
                Throwable c = e.getCause() != null ? e.getCause() : e;
                throw new IOException("нет связи с " + baseUrl + ": " + c.getMessage(), c);
            } finally {
                inFlight = null;
            }
            int sc = resp.statusCode();
            if (sc < 400) return Json.parse(resp.body());
            if ((sc == 429 || sc >= 500) && attempt < MAX_RETRIES) {
                long wait = resp.headers().firstValue("retry-after").map(ApiAgent::seconds).orElse(2L * (attempt + 1));
                listener.onSystem("Сервис вернул HTTP " + sc + ", повтор через " + wait + " c…");
                sleep(wait);
                continue;
            }
            throw new IOException("HTTP " + sc + ": " + errorMessage(resp.body()));
        }
    }

    private static String errorMessage(String body) {
        try {
            Object o = Json.parse(body);
            Map<String, Object> err = Json.obj(o, "error");
            String m = err != null ? Json.str(err, "message") : Json.str(o, "message");
            if (m != null) return m;
        } catch (RuntimeException ignored) {
            // not JSON
        }
        return cut(body, 300);
    }

    private void checkCancelled() {
        if (cancelled) throw new CancellationException();
    }

    private void sleep(long seconds) {
        try {
            Thread.sleep(Math.min(seconds, 30) * 1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CancellationException();
        }
    }

    private static long seconds(String v) {
        try {
            return Math.max(1, (long) Double.parseDouble(v.trim()));
        } catch (NumberFormatException e) {
            return 2;
        }
    }

    private static long num(Object o) {
        return o instanceof Double d ? d.longValue() : 0;
    }

    private static String cut(String s, int max) {
        if (s == null) return "";
        if (max <= 0 || s.length() <= max) return s;
        return s.substring(0, max) + "…";
    }

    private static String trimSlash(String s) {
        s = s == null ? "" : s.trim();
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
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
        return cut(b.toString(), 140);
    }
}
