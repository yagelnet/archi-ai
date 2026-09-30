package local.archi.ai;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;

import com.archimatetool.editor.model.IEditorModelManager;
import com.archimatetool.model.IArchimateModel;

/** Paths and configuration shared by the view and the terminal command. */
final class ClaudeEnv {
    static final String MCP_URL = System.getProperty("archi.claude.mcpUrl", "http://127.0.0.1:18090/mcp");

    /** Tools Claude may use without asking: the Archi MCP server plus read-only file tools. */
    static final String ALLOWED_TOOLS = "mcp__archi__*,Read,Glob,Grep";

    static final String SYSTEM_PROMPT =
        "Ты работаешь как ассистент архитектора внутри Archi (ArchiMate 3.1). "
        + "Для чтения и изменения модели используй MCP-сервер archi (инструменты mcp__archi__*). "
        + "Сервер работает с одной активной моделью: перед изменениями вызови get-model-info и сверь имя модели. "
        + "Крупные изменения делай одним вызовом bulk-mutate. "
        + "Сообщения с префиксом [Контекст Archi] описывают выделение пользователя в Archi. "
        + "Отвечай по-русски, кратко и по делу.";

    private ClaudeEnv() {}

    /** Claude Code executable: env CLAUDE_CODE_PATH, then ~/.local/bin/claude(.exe), then PATH. */
    static String claudeExecutable() {
        String env = System.getenv("CLAUDE_CODE_PATH");
        if (env != null && new File(env).isFile()) return env;
        File home = new File(System.getProperty("user.home"));
        File exe = new File(home, ".local/bin/claude.exe");
        if (exe.isFile()) return exe.getAbsolutePath();
        File bin = new File(home, ".local/bin/claude");
        if (bin.isFile()) return bin.getAbsolutePath();
        return "claude";
    }

    /**
     * MCP servers used besides Archi, by name → URL: the Jira/Confluence plugin for Archi
     * (local.archi.atlassian, http://127.0.0.1:18091/mcp) when it is running.
     */
    static Map<String, String> extraServers() {
        Map<String, String> r = new LinkedHashMap<>();
        String url = AiSettings.atlassianMcpUrl();
        if (listening(url)) r.put("atlassian", url);
        return r;
    }

    private static boolean listening(String url) {
        try {
            URI u = URI.create(url);
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress(u.getHost(), u.getPort() > 0 ? u.getPort() : 80), 500);
                return true;
            }
        } catch (IOException | IllegalArgumentException e) {
            return false;
        }
    }

    /** Writes the MCP config for Claude Code: the Archi server plus {@link #extraServers()}. */
    static File mcpConfig() throws IOException {
        File dir = new File(System.getProperty("user.home"), ".archi-claude");
        dir.mkdirs();
        File f = new File(dir, "mcp-archi.json");
        Map<String, Object> servers = Json.map("archi", Json.map("type", "http", "url", AiSettings.mcpUrl()));
        for (Map.Entry<String, String> e : extraServers().entrySet()) servers.put(e.getKey(), Json.map("type", "http", "url", e.getValue()));
        Files.writeString(f.toPath(), Json.write(Json.map("mcpServers", servers)), StandardCharsets.UTF_8);
        return f;
    }

    /** {@link #ALLOWED_TOOLS} plus every tool of the additional MCP servers. */
    static String allowedTools() {
        StringBuilder b = new StringBuilder(ALLOWED_TOOLS);
        for (String name : extraServers().keySet()) b.append(",mcp__").append(name).append("__*");
        return b.toString();
    }

    /** {@link #SYSTEM_PROMPT} with a note on Jira/Confluence when that server is running. */
    static String systemPrompt() {
        if (!extraServers().containsKey("atlassian")) return SYSTEM_PROMPT;
        return SYSTEM_PROMPT + " Для Jira, реестра ITAM (Jira Insight) и Confluence есть MCP-сервер atlassian — "
                + "используй его, когда нужны данные вне модели Archi.";
    }

    static final String DEFAULT_RULES = String.join("\n",
        "Правила моделирования (ArchiMate 3.1):",
        "1. Перед любыми изменениями вызови get-model-info и убедись, что активна нужная модель. Если модель не та — остановись и сообщи.",
        "2. Перед созданием элемента найди существующий (search-elements) и переиспользуй его, не создавай дубли.",
        "3. Названия давай без сокращений, на языке модели и в том же стиле, что у существующих элементов.",
        "4. Бизнес-возможности — Capability (слой Strategy). Возможность L2 входит в L1 через композицию и вложена в неё на схеме.",
        "5. Цели — Goal (слой Motivation). Возможность реализует цель связью realization; драйверы и оценки влияют на цели связью influence.",
        "6. Приложения — ApplicationComponent. Вызов сервиса — serving (от публикующего к вызывающему), передача данных — flow.",
        "7. Новые элементы клади в тематические подпапки своего слоя, не в корень.",
        "8. Дополнительные атрибуты (источник, владелец, KPI и т. п.) храни в свойствах (properties), а не в названии.",
        "9. Пакетные изменения делай одним bulk-mutate. После изменения представления проверь раскладку (export-view) и сохрани модель (save-model).",
        "10. Ничего не удаляй без явного подтверждения пользователя.");

    static File rulesFile() {
        return new File(new File(System.getProperty("user.home"), ".archi-claude"), "modeling-rules.md");
    }

    /** Modeling rules text; creates the file with the default rules on first use. */
    static String loadRules() {
        File f = rulesFile();
        try {
            if (!f.isFile()) saveRules(DEFAULT_RULES);
            return Files.readString(f.toPath(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return DEFAULT_RULES;
        }
    }

    static void saveRules(String text) throws IOException {
        File f = rulesFile();
        f.getParentFile().mkdirs();
        Files.writeString(f.toPath(), text, StandardCharsets.UTF_8);
    }

    /** Folder of the given model's file, falling back to {@link #workingDir()}. */
    static File workingDir(IArchimateModel model) {
        if (model != null && model.getFile() != null) {
            File p = model.getFile().getParentFile();
            if (p != null && p.isDirectory()) return p;
        }
        return workingDir();
    }

    /** Folder of the first saved open model, or the user home. */
    static File workingDir() {
        for (IArchimateModel m : IEditorModelManager.INSTANCE.getModels()) {
            File f = m.getFile();
            if (f != null && f.getParentFile() != null && f.getParentFile().isDirectory()) return f.getParentFile();
        }
        return new File(System.getProperty("user.home"));
    }
}
