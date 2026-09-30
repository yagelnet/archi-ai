package local.archi.ai;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Properties;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.eclipse.equinox.security.storage.ISecurePreferences;
import org.eclipse.equinox.security.storage.SecurePreferencesFactory;
import org.eclipse.equinox.security.storage.StorageException;

/**
 * Connection settings: mode (Claude Code subscription or direct API), provider, endpoint, model.
 * Everything lives in ~/.archi-claude/settings.properties; API keys are stored there AES-encrypted,
 * falling back to the provider's environment variable.
 */
final class AiSettings {

    static final String MODE_CLAUDE_CODE = "claude-code";
    static final String MODE_API = "api";

    enum Protocol { ANTHROPIC, OPENAI }

    record Provider(String id, String label, Protocol protocol, String baseUrl, String model, String envKey, boolean keyRequired) {}

    /** Presets. The model field is only a starting value — every provider can use any model it serves. */
    static final List<Provider> PROVIDERS = List.of(
        new Provider("anthropic", "Anthropic (Claude API)", Protocol.ANTHROPIC, "https://api.anthropic.com", "claude-opus-5", "ANTHROPIC_API_KEY", true),
        new Provider("openai", "OpenAI", Protocol.OPENAI, "https://api.openai.com/v1", "", "OPENAI_API_KEY", true),
        new Provider("openrouter", "OpenRouter", Protocol.OPENAI, "https://openrouter.ai/api/v1", "", "OPENROUTER_API_KEY", true),
        new Provider("deepseek", "DeepSeek", Protocol.OPENAI, "https://api.deepseek.com/v1", "deepseek-chat", "DEEPSEEK_API_KEY", true),
        new Provider("mistral", "Mistral", Protocol.OPENAI, "https://api.mistral.ai/v1", "mistral-large-latest", "MISTRAL_API_KEY", true),
        new Provider("zai", "Z.ai (GLM)", Protocol.OPENAI, "https://api.z.ai/api/paas/v4", "glm-5.3", "ZAI_API_KEY", true),
        new Provider("zai-coding", "Z.ai — GLM Coding Plan (подписка)", Protocol.OPENAI, "https://api.z.ai/api/coding/paas/v4", "glm-5.2", "ZAI_API_KEY", true),
        new Provider("yandex", "YandexGPT (OpenAI-совместимый)", Protocol.OPENAI, "https://llm.api.cloud.yandex.net/v1", "gpt://<folder_id>/yandexgpt/latest", "YANDEX_API_KEY", true),
        new Provider("ollama", "Ollama (локально)", Protocol.OPENAI, "http://localhost:11434/v1", "", "", false),
        new Provider("lmstudio", "LM Studio (локально)", Protocol.OPENAI, "http://localhost:1234/v1", "", "", false),
        new Provider("custom", "Другой OpenAI-совместимый", Protocol.OPENAI, "", "", "", false));

    private static final String SECURE_NODE = "local.archi.claude";
    private static final Properties props = new Properties();
    private static boolean loaded;

    private AiSettings() {}

    static File file() {
        return new File(new File(System.getProperty("user.home"), ".archi-claude"), "settings.properties");
    }

    private static synchronized void ensureLoaded() {
        if (loaded) return;
        loaded = true;
        File f = file();
        if (!f.isFile()) return;
        try (InputStream in = new FileInputStream(f)) {
            props.load(in);
        } catch (IOException ignored) {
            // defaults apply
        }
    }

    static synchronized String get(String key, String def) {
        ensureLoaded();
        String v = props.getProperty(key);
        return v == null ? def : v;
    }

    static boolean bool(String key, boolean def) {
        return Boolean.parseBoolean(get(key, String.valueOf(def)));
    }

    static int integer(String key, int def) {
        try {
            return Integer.parseInt(get(key, String.valueOf(def)).trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    static synchronized void set(String key, String value) {
        ensureLoaded();
        if (value == null) props.remove(key); else props.setProperty(key, value);
    }

    static synchronized void save() {
        ensureLoaded();
        File f = file();
        f.getParentFile().mkdirs();
        try (OutputStream out = new FileOutputStream(f)) {
            props.store(out, "Arch AI");
        } catch (IOException ignored) {
            // not critical
        }
    }

    // ---- typed accessors ----

    static String mode() { return get("mode", MODE_CLAUDE_CODE); }

    static String mcpUrl() { return get("mcp.url", "http://127.0.0.1:18090/mcp"); }

    /** MCP server of the Jira/Confluence plugin for Archi; used only while it is running. */
    static String atlassianMcpUrl() { return get("mcp.atlassian.url", "http://127.0.0.1:18091/mcp"); }

    static Provider provider() {
        String id = get("api.provider", "anthropic");
        return PROVIDERS.stream().filter(p -> p.id().equals(id)).findFirst().orElse(PROVIDERS.get(0));
    }

    static String baseUrl() { return get("api.baseUrl." + provider().id(), provider().baseUrl()); }

    static String model() { return get("api.model." + provider().id(), provider().model()); }

    static int maxTokens() { return 16000; }

    /** Tool descriptions from the MCP server are long; they are cut to this many characters (0 = no limit). */
    static int toolDescMax() { return 800; }


    // ---- API keys ----
    // Stored in settings.properties as "api.key.<id>=enc:<base64(iv|AES-GCM ciphertext)>".
    // The AES key is a random value in ~/.archi-claude/.secret, so the settings file alone does not reveal the API key.

    private static final String ENC_PREFIX = "enc:";

    static String apiKey(Provider p) {
        migrateFromSecureStorage(p);
        String stored = decrypt(get("api.key." + p.id(), ""));
        if (!stored.isBlank()) return stored;
        if (!p.envKey().isEmpty()) {
            String env = System.getenv(p.envKey());
            if (env != null && !env.isBlank()) return env;
        }
        return "";
    }

    static boolean hasStoredKey(Provider p) {
        migrateFromSecureStorage(p);
        return !decrypt(get("api.key." + p.id(), "")).isBlank();
    }

    static void storeApiKey(Provider p, String key) throws IOException {
        if (key == null || key.isBlank()) set("api.key." + p.id(), null);
        else set("api.key." + p.id(), encrypt(key.trim()));
        save();
    }

    /** Keys saved by versions up to 1.3.9 live in Eclipse secure storage: move them to the settings file once. */
    private static void migrateFromSecureStorage(Provider p) {
        if (!get("api.key." + p.id(), "").isEmpty()) return;
        try {
            ISecurePreferences node = SecurePreferencesFactory.getDefault().node(SECURE_NODE);
            String k = node.get("apikey." + p.id(), null);
            if (k == null || k.isBlank()) return;
            storeApiKey(p, k);
            node.remove("apikey." + p.id());
            node.flush();
        } catch (StorageException | IOException | RuntimeException ignored) {
            // nothing to migrate
        }
    }

    private static synchronized SecretKey secret() throws IOException, GeneralSecurityException {
        File f = new File(file().getParentFile(), ".secret");
        if (!f.isFile()) {
            f.getParentFile().mkdirs();
            byte[] k = new byte[32];
            new SecureRandom().nextBytes(k);
            Files.write(f.toPath(), k);
            try {
                Files.setAttribute(f.toPath(), "dos:hidden", true);
            } catch (IOException | UnsupportedOperationException ignored) {
                // cosmetic
            }
        }
        return new SecretKeySpec(Files.readAllBytes(f.toPath()), "AES");
    }

    private static String encrypt(String plain) throws IOException {
        try {
            byte[] iv = new byte[12];
            new SecureRandom().nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, secret(), new GCMParameterSpec(128, iv));
            byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return ENC_PREFIX + Base64.getEncoder().encodeToString(out);
        } catch (GeneralSecurityException e) {
            throw new IOException("не удалось зашифровать ключ: " + e.getMessage(), e);
        }
    }

    private static String decrypt(String stored) {
        if (stored == null || !stored.startsWith(ENC_PREFIX)) return "";
        try {
            byte[] in = Base64.getDecoder().decode(stored.substring(ENC_PREFIX.length()));
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, secret(), new GCMParameterSpec(128, in, 0, 12));
            return new String(c.doFinal(in, 12, in.length - 12), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IOException | IllegalArgumentException e) {
            return "";
        }
    }
}
