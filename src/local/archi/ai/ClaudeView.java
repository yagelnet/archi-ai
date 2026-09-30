package local.archi.ai;

import java.io.IOException;

import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CTabFolder;
import org.eclipse.swt.custom.CTabItem;
import org.eclipse.swt.custom.StyleRange;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Spinner;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.ISelectionListener;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.part.ViewPart;

import com.archimatetool.model.IArchimateModel;

import local.archi.ai.AiSettings.Provider;

/**
 * Docked AI panel for Archi with three tabs: chat, modeling rules and connection settings.
 * The chat runs either through Claude Code (the user's subscription) or through a direct API
 * connection ({@link ApiAgent}); in both cases the model is read and changed via the Archi MCP server,
 * which the panel keeps pointed at the model the user is working with (see {@link ModelSync}).
 */
public class ClaudeView extends ViewPart implements ClaudeProcess.Listener {

    public static final String ID = "local.archi.ai.view";

    private enum Kind { USER, CLAUDE, TOOL, SYSTEM, ERROR }

    // chat tab
    private StyledText output;
    private Text input;
    private Button sendBtn;
    private Button stopBtn;
    private Button contextBtn;
    private Label connLabel;
    private Label modelLabel;
    private Label status;
    private Kind lastKind;
    private boolean busy;

    // rules tab
    private CTabItem rulesTab;
    private StyledText rulesText;
    private Button rulesOnBtn;
    private boolean rulesDirty;

    // connection tab
    private Button modeCcBtn;
    private Button modeApiBtn;
    private Combo providerCombo;
    private Text baseUrlText;
    private Text modelText;
    private Text keyText;
    private Label keyHint;
    private Text mcpUrlText;
    private Label connStatus;
    private Group apiGroup;

    private IStructuredSelection lastSelection;
    private ChatBackend backend;

    private final ISelectionListener selectionListener = (IWorkbenchPart part, ISelection sel) -> {
        if (part != ClaudeView.this && sel instanceof IStructuredSelection ss && !ss.isEmpty()) {
            lastSelection = ss;
            syncModel(false);
        }
    };

    private ChatBackend backend() {
        if (backend == null) {
            backend = AiSettings.MODE_API.equals(AiSettings.mode()) ? new ApiAgent(this) : new ClaudeProcess(this);
        }
        return backend;
    }

    @Override
    public void createPartControl(Composite parent) {
        parent.setLayout(new GridLayout(1, false));
        CTabFolder tabs = new CTabFolder(parent, SWT.BOTTOM | SWT.FLAT);
        tabs.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        CTabItem chatTab = new CTabItem(tabs, SWT.NONE);
        chatTab.setText("Чат");
        chatTab.setControl(createChat(tabs));

        rulesTab = new CTabItem(tabs, SWT.NONE);
        rulesTab.setText("Правила моделирования");
        rulesTab.setControl(createRules(tabs));

        CTabItem connTab = new CTabItem(tabs, SWT.NONE);
        connTab.setText("Подключение");
        connTab.setControl(createConnection(tabs));

        tabs.setSelection(chatTab);

        getSite().getPage().addPostSelectionListener(selectionListener);
        ISelection current = getSite().getPage().getSelection();
        if (current instanceof IStructuredSelection ss && !ss.isEmpty()) lastSelection = ss;
        syncModel(true);
        updateConnLabel();

        append(Kind.SYSTEM, "Arch AI — ИИ-ассистент Archi. Модель читается и меняется через MCP-сервер Archi " + AiSettings.mcpUrl()
                + ". Режим подключения выбирается на вкладке «Подключение».");
    }

    // ================= chat tab =================

    private Composite createChat(Composite parent) {
        Composite c = new Composite(parent, SWT.NONE);
        c.setLayout(new GridLayout(1, false));

        Composite bar = new Composite(c, SWT.NONE);
        bar.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        GridLayout bl = new GridLayout(4, false);
        bl.marginWidth = 0;
        bl.marginHeight = 0;
        bar.setLayout(bl);

        Button newBtn = new Button(bar, SWT.PUSH);
        newBtn.setText("Новый диалог");
        newBtn.setToolTipText("Завершить текущий диалог и начать новый");
        newBtn.addListener(SWT.Selection, e -> newConversation());

        stopBtn = new Button(bar, SWT.PUSH);
        stopBtn.setText("Остановить");
        stopBtn.setToolTipText("Прервать текущий ответ. Диалог продолжится со следующего сообщения");
        stopBtn.setEnabled(false);
        stopBtn.addListener(SWT.Selection, e -> stopTurn());

        contextBtn = new Button(bar, SWT.CHECK);
        contextBtn.setText("Добавлять выделение");
        contextBtn.setToolTipText("Прикладывать к запросу выделенные в Archi элементы и представления");
        contextBtn.setSelection(AiSettings.bool("context.enabled", true));
        contextBtn.addListener(SWT.Selection, e -> { AiSettings.set("context.enabled", String.valueOf(contextBtn.getSelection())); AiSettings.save(); });

        status = new Label(bar, SWT.RIGHT);
        status.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        status.setText("Готово");

        connLabel = new Label(c, SWT.NONE);
        connLabel.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));

        modelLabel = new Label(c, SWT.NONE);
        modelLabel.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        modelLabel.setText("Модель Archi: не выбрана — выделите элемент или откройте представление");

        output = new StyledText(c, SWT.MULTI | SWT.WRAP | SWT.V_SCROLL | SWT.READ_ONLY | SWT.BORDER);
        output.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        output.setLeftMargin(6);
        output.setRightMargin(6);

        Composite attRow = new Composite(c, SWT.NONE);
        attRow.setLayoutData(new GridData(SWT.FILL, SWT.BOTTOM, true, false));
        GridLayout al = new GridLayout(3, false);
        al.marginWidth = 0;
        al.marginHeight = 0;
        attRow.setLayout(al);
        Button addFiles = new Button(attRow, SWT.PUSH);
        addFiles.setText("Файлы…");
        addFiles.setToolTipText("Приложить файлы к запросу: таблицы, документы, PDF, изображения. Их обработает модель. Можно перетащить файлы в поле ввода");
        addFiles.addListener(SWT.Selection, e -> chooseFiles());
        Button clearFiles = new Button(attRow, SWT.PUSH);
        clearFiles.setText("Убрать");
        clearFiles.setToolTipText("Убрать все вложения");
        clearFiles.addListener(SWT.Selection, e -> { attachments.clear(); updateAttachLabel(); });
        attachLabel = new Label(attRow, SWT.NONE);
        attachLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        updateAttachLabel();

        Composite inRow = new Composite(c, SWT.NONE);
        inRow.setLayoutData(new GridData(SWT.FILL, SWT.BOTTOM, true, false));
        GridLayout il = new GridLayout(2, false);
        il.marginWidth = 0;
        il.marginHeight = 0;
        inRow.setLayout(il);

        input = new Text(inRow, SWT.MULTI | SWT.WRAP | SWT.V_SCROLL | SWT.BORDER);
        GridData igd = new GridData(SWT.FILL, SWT.FILL, true, false);
        igd.heightHint = input.getLineHeight() * 16;
        input.setLayoutData(igd);
        input.setMessage("Вопрос или задача по модели. Ctrl+Enter — отправить");
        input.addListener(SWT.KeyDown, e -> {
            if ((e.keyCode == SWT.CR || e.keyCode == SWT.KEYPAD_CR) && (e.stateMask & SWT.MOD1) != 0) {
                e.doit = false;
                send();
            }
        });

        sendBtn = new Button(inRow, SWT.PUSH);
        sendBtn.setText("Отправить");
        sendBtn.setLayoutData(new GridData(SWT.FILL, SWT.FILL, false, true));
        sendBtn.addListener(SWT.Selection, e -> send());

        // drag & drop files onto the input or the conversation
        for (org.eclipse.swt.widgets.Control target : new org.eclipse.swt.widgets.Control[] {input, output}) {
            org.eclipse.swt.dnd.DropTarget dt = new org.eclipse.swt.dnd.DropTarget(target, org.eclipse.swt.dnd.DND.DROP_COPY | org.eclipse.swt.dnd.DND.DROP_DEFAULT);
            dt.setTransfer(org.eclipse.swt.dnd.FileTransfer.getInstance());
            dt.addDropListener(new org.eclipse.swt.dnd.DropTargetAdapter() {
                @Override
                public void dragEnter(org.eclipse.swt.dnd.DropTargetEvent ev) { ev.detail = org.eclipse.swt.dnd.DND.DROP_COPY; }
                @Override
                public void drop(org.eclipse.swt.dnd.DropTargetEvent ev) {
                    if (ev.data instanceof String[] paths) addFiles(paths);
                }
            });
        }
        return c;
    }

    private final java.util.List<Attachment> attachments = new java.util.ArrayList<>();
    private Label attachLabel;

    private void chooseFiles() {
        org.eclipse.swt.widgets.FileDialog fd = new org.eclipse.swt.widgets.FileDialog(input.getShell(), SWT.OPEN | SWT.MULTI);
        fd.setText("Файлы для ИИ");
        fd.setFilterNames(new String[] {"Все файлы", "Таблицы", "Документы", "Изображения"});
        fd.setFilterExtensions(new String[] {"*.*", "*.xlsx;*.xlsm;*.xls;*.csv", "*.docx;*.doc;*.pdf;*.txt;*.md", "*.png;*.jpg;*.jpeg;*.gif;*.webp"});
        if (fd.open() == null) return;
        String dir = fd.getFilterPath();
        String[] names = fd.getFileNames();
        String[] paths = new String[names.length];
        for (int i = 0; i < names.length; i++) paths[i] = new java.io.File(dir, names[i]).getAbsolutePath();
        addFiles(paths);
    }

    private void addFiles(String[] paths) {
        for (String p : paths) {
            java.io.File f = new java.io.File(p);
            if (!f.isFile()) continue;
            if (attachments.stream().noneMatch(a -> a.file().equals(f))) attachments.add(Attachment.of(f));
        }
        updateAttachLabel();
    }

    private void updateAttachLabel() {
        if (attachLabel == null || attachLabel.isDisposed()) return;
        if (attachments.isEmpty()) {
            attachLabel.setText("");
        } else {
            StringBuilder b = new StringBuilder("Вложения: ");
            for (int i = 0; i < attachments.size(); i++) {
                if (i > 0) b.append(", ");
                b.append(attachments.get(i).name()).append(" (").append(attachments.get(i).sizeLabel()).append(')');
            }
            attachLabel.setText(b.toString());
        }
        attachLabel.getParent().layout();
    }

    private void updateConnLabel() {
        if (connLabel != null && !connLabel.isDisposed()) {
            connLabel.setText("Подключение: " + backend().label());
            connLabel.getParent().layout();
        }
    }

    /** Points the MCP server at the model the user works with and shows it in the panel. */
    private IArchimateModel syncModel(boolean force) {
        IArchimateModel m = ModelSync.target(lastSelection, getSite().getPage());
        if (m == null) m = ModelSync.lastAnnounced();
        if (m != null) {
            boolean switched = ModelSync.activate(m, force);
            if (modelLabel != null && !modelLabel.isDisposed()) {
                modelLabel.setText("Модель Archi: «" + m.getName() + "»"
                        + (m.getFile() != null ? "  —  " + m.getFile().getAbsolutePath() : "  —  не сохранена"));
                modelLabel.getParent().layout();
            }
            if (switched && !force) append(Kind.SYSTEM, "MCP переключён на модель «" + m.getName() + "».");
        }
        return m;
    }

    private void send() {
        String text = input.getText().trim();
        if (text.isEmpty() || busy) return;

        IArchimateModel model = syncModel(true);

        StringBuilder message = new StringBuilder();
        if (rulesOnBtn.getSelection()) {
            if (rulesDirty) saveRules();
            String rules = rulesText.getText().trim();
            if (!rules.isEmpty()) message.append("[Правила моделирования]\n").append(rules).append("\n\n");
        }
        if (contextBtn.getSelection()) message.append(ArchiContext.describe(lastSelection, model)).append("\n\n");
        message.append(text);

        final java.util.List<Attachment> files = java.util.List.copyOf(attachments);
        StringBuilder shown = new StringBuilder(text);
        if (!files.isEmpty()) {
            shown.append("\n[вложения: ");
            for (int i = 0; i < files.size(); i++) shown.append(i > 0 ? ", " : "").append(files.get(i).name());
            shown.append(']');
        }
        append(Kind.USER, shown.toString());
        input.setText("");
        attachments.clear();
        updateAttachLabel();
        ChatBackend b = backend();
        final String msg = message.toString();
        setBusy(true);
        setStatus(b.isRunning() ? "Думает…" : (b.sessionId() == null ? "Подключение…" : "Продолжение диалога…"));
        // starting may take a while (process launch, MCP tool list) — do it off the UI thread
        Thread t = new Thread(() -> {
            try {
                if (!b.isRunning()) b.start(ClaudeEnv.workingDir(model));
                b.send(msg, files);
                ui(() -> setStatus("Думает…"));
            } catch (IOException ex) {
                ui(() -> {
                    append(Kind.ERROR, "Не удалось отправить запрос: " + ex.getMessage());
                    setBusy(false);
                    setStatus("Ошибка");
                });
            }
        }, "archi-ai-send");
        t.setDaemon(true);
        t.start();
    }

    private void stopTurn() {
        backend().stop();
        setBusy(false);
        append(Kind.SYSTEM, "Ответ прерван. Следующее сообщение продолжит этот же диалог.");
        setStatus("Остановлено");
    }

    private void newConversation() {
        backend().reset();
        setBusy(false);
        output.setText("");
        lastKind = null;
        append(Kind.SYSTEM, "Новый диалог · " + backend().label());
        setStatus("Готово");
    }

    private void setBusy(boolean b) {
        busy = b;
        if (sendBtn.isDisposed()) return;
        sendBtn.setEnabled(!b);
        stopBtn.setEnabled(b);
    }

    private void setStatus(String s) {
        if (!status.isDisposed()) {
            status.setText(s);
            status.getParent().layout();
        }
    }

    // ================= rules tab =================

    private Composite createRules(Composite parent) {
        Composite c = new Composite(parent, SWT.NONE);
        c.setLayout(new GridLayout(1, false));

        Label hint = new Label(c, SWT.WRAP);
        GridData hgd = new GridData(SWT.FILL, SWT.TOP, true, false);
        hgd.widthHint = 300;
        hint.setLayoutData(hgd);
        hint.setText("Этот текст отправляется ИИ перед каждым запросом из чата, до контекста выделения и самого запроса. "
                + "В терминальном режиме Claude Code правила добавляются к системной инструкции при запуске. Файл: "
                + ClaudeEnv.rulesFile().getAbsolutePath());

        rulesOnBtn = new Button(c, SWT.CHECK);
        rulesOnBtn.setText("Отправлять правила перед каждым запросом");
        rulesOnBtn.setSelection(AiSettings.bool("rules.enabled", true));
        rulesOnBtn.addListener(SWT.Selection, e -> { AiSettings.set("rules.enabled", String.valueOf(rulesOnBtn.getSelection())); AiSettings.save(); });

        rulesText = new StyledText(c, SWT.MULTI | SWT.WRAP | SWT.V_SCROLL | SWT.BORDER);
        rulesText.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        rulesText.setFont(JFaceResources.getTextFont());
        rulesText.setLeftMargin(6);
        rulesText.setText(ClaudeEnv.loadRules());
        rulesText.addModifyListener(e -> setRulesDirty(true));

        Composite bar = new Composite(c, SWT.NONE);
        bar.setLayoutData(new GridData(SWT.FILL, SWT.BOTTOM, true, false));
        GridLayout bl = new GridLayout(3, false);
        bl.marginWidth = 0;
        bl.marginHeight = 0;
        bar.setLayout(bl);

        Button save = new Button(bar, SWT.PUSH);
        save.setText("Сохранить правила");
        save.addListener(SWT.Selection, e -> saveRules());

        Button reset = new Button(bar, SWT.PUSH);
        reset.setText("Вернуть шаблон");
        reset.setToolTipText("Заменить текст шаблоном по умолчанию. Сохранится после «Сохранить правила»");
        reset.addListener(SWT.Selection, e -> rulesText.setText(ClaudeEnv.DEFAULT_RULES));

        Button reload = new Button(bar, SWT.PUSH);
        reload.setText("Перечитать файл");
        reload.addListener(SWT.Selection, e -> { rulesText.setText(ClaudeEnv.loadRules()); setRulesDirty(false); });

        setRulesDirty(false);
        return c;
    }

    private void setRulesDirty(boolean d) {
        rulesDirty = d;
        if (rulesTab != null && !rulesTab.isDisposed()) rulesTab.setText(d ? "Правила моделирования *" : "Правила моделирования");
    }

    private boolean saveRules() {
        try {
            ClaudeEnv.saveRules(rulesText.getText());
            setRulesDirty(false);
            return true;
        } catch (IOException ex) {
            append(Kind.ERROR, "Не удалось сохранить правила: " + ex.getMessage());
            return false;
        }
    }

    // ================= connection tab =================

    private Composite createConnection(Composite parent) {
        Composite c = new Composite(parent, SWT.NONE);
        c.setLayout(new GridLayout(1, false));

        Group modeGroup = new Group(c, SWT.NONE);
        modeGroup.setText("Как подключаться к ИИ");
        modeGroup.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        modeGroup.setLayout(new GridLayout(1, false));
        modeCcBtn = new Button(modeGroup, SWT.RADIO);
        modeCcBtn.setText("Claude Code (подписка)");
        modeApiBtn = new Button(modeGroup, SWT.RADIO);
        modeApiBtn.setText("API");
        boolean api = AiSettings.MODE_API.equals(AiSettings.mode());
        modeCcBtn.setSelection(!api);
        modeApiBtn.setSelection(api);
        modeCcBtn.addListener(SWT.Selection, e -> updateApiEnabled());
        modeApiBtn.addListener(SWT.Selection, e -> updateApiEnabled());

        apiGroup = new Group(c, SWT.NONE);
        apiGroup.setText("Подключение через API");
        apiGroup.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        apiGroup.setLayout(new GridLayout(2, false));

        new Label(apiGroup, SWT.NONE).setText("Сервис");
        providerCombo = new Combo(apiGroup, SWT.READ_ONLY | SWT.DROP_DOWN);
        providerCombo.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        for (Provider p : AiSettings.PROVIDERS) providerCombo.add(p.label());
        providerCombo.select(AiSettings.PROVIDERS.indexOf(AiSettings.provider()));
        providerCombo.addListener(SWT.Selection, e -> loadProviderFields());

        new Label(apiGroup, SWT.NONE).setText("Адрес API");
        baseUrlText = new Text(apiGroup, SWT.BORDER);
        baseUrlText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        new Label(apiGroup, SWT.NONE).setText("Модель");
        modelText = new Text(apiGroup, SWT.BORDER);
        modelText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        modelText.setMessage("идентификатор модели у провайдера; модель должна поддерживать вызов инструментов");

        new Label(apiGroup, SWT.NONE).setText("API-ключ");
        keyText = new Text(apiGroup, SWT.BORDER | SWT.PASSWORD);
        keyText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        new Label(apiGroup, SWT.NONE);
        keyHint = new Label(apiGroup, SWT.WRAP);
        keyHint.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        Group common = new Group(c, SWT.NONE);
        common.setText("MCP-сервер Archi");
        common.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        common.setLayout(new GridLayout(2, false));
        new Label(common, SWT.NONE).setText("Адрес");
        mcpUrlText = new Text(common, SWT.BORDER);
        mcpUrlText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        mcpUrlText.setText(AiSettings.mcpUrl());

        Composite bar = new Composite(c, SWT.NONE);
        bar.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        GridLayout bl = new GridLayout(2, false);
        bl.marginWidth = 0;
        bar.setLayout(bl);
        Button save = new Button(bar, SWT.PUSH);
        save.setText("Сохранить");
        save.addListener(SWT.Selection, e -> saveConnection());
        Button test = new Button(bar, SWT.PUSH);
        test.setText("Сохранить и проверить подключение");
        test.addListener(SWT.Selection, e -> { if (saveConnection()) testConnection(); });

        connStatus = new Label(c, SWT.WRAP);
        GridData sgd = new GridData(SWT.FILL, SWT.TOP, true, false);
        sgd.widthHint = 300;
        connStatus.setLayoutData(sgd);

        loadProviderFields();
        updateApiEnabled();
        return c;
    }

    private Provider selectedProvider() {
        int i = providerCombo.getSelectionIndex();
        return AiSettings.PROVIDERS.get(i < 0 ? 0 : i);
    }

    private void loadProviderFields() {
        Provider p = selectedProvider();
        baseUrlText.setText(AiSettings.get("api.baseUrl." + p.id(), p.baseUrl()));
        modelText.setText(AiSettings.get("api.model." + p.id(), p.model()));
        keyText.setText("");
        String hint;
        if (AiSettings.hasStoredKey(p)) hint = "Ключ сохранён в файле настроек в зашифрованном виде. Оставьте поле пустым, чтобы не менять его.";
        else if (!p.envKey().isEmpty() && System.getenv(p.envKey()) != null) hint = "Ключ берётся из переменной окружения " + p.envKey() + ".";
        else if (!p.keyRequired()) hint = "Для локального сервиса ключ обычно не нужен.";
        else hint = "";
        keyHint.setText(hint);
        keyHint.getParent().layout();
    }

    private void updateApiEnabled() {
        boolean api = modeApiBtn.getSelection();
        for (var ch : apiGroup.getChildren()) ch.setEnabled(api);
        apiGroup.setEnabled(api);
    }

    private boolean saveConnection() {
        String oldLabel = backend().label();
        Provider p = selectedProvider();
        AiSettings.set("mode", modeApiBtn.getSelection() ? AiSettings.MODE_API : AiSettings.MODE_CLAUDE_CODE);
        AiSettings.set("api.provider", p.id());
        AiSettings.set("api.baseUrl." + p.id(), baseUrlText.getText().trim());
        AiSettings.set("api.model." + p.id(), modelText.getText().trim());
        AiSettings.set("mcp.url", mcpUrlText.getText().trim());
        AiSettings.save();
        try {
            if (!keyText.getText().isBlank()) AiSettings.storeApiKey(p, keyText.getText());
        } catch (IOException ex) {
            connStatus.setText("Настройки сохранены, но ключ сохранить не удалось: " + ex.getMessage());
            return false;
        }
        loadProviderFields();

        // switch the chat to the new connection; the next message starts a new conversation
        if (backend != null) backend.reset();
        backend = null;
        String newLabel = backend().label();
        updateConnLabel();
        setBusy(false);
        connStatus.setText("Сохранено. Подключение: " + newLabel);
        if (!newLabel.equals(oldLabel)) append(Kind.SYSTEM, "Подключение изменено: " + newLabel + ". Следующий запрос начнёт новый диалог.");
        return true;
    }

    private void testConnection() {
        connStatus.setText("Проверка…");
        boolean api = AiSettings.MODE_API.equals(AiSettings.mode());
        Thread t = new Thread(() -> {
            StringBuilder r = new StringBuilder();
            try {
                int n = new McpClient(AiSettings.mcpUrl()).listTools().size();
                r.append("MCP-сервер Archi: доступен, инструментов ").append(n).append(". ");
            } catch (IOException ex) {
                r.append("MCP-сервер Archi: ошибка — ").append(ex.getMessage()).append(". ");
            }
            for (java.util.Map.Entry<String, String> s : ClaudeEnv.extraServers().entrySet()) {
                try {
                    int n = new McpClient(s.getValue()).listTools().size();
                    r.append("MCP ").append(s.getKey()).append(": инструментов ").append(n).append(". ");
                } catch (IOException ex) {
                    r.append("MCP ").append(s.getKey()).append(": ошибка — ").append(ex.getMessage()).append(". ");
                }
            }
            if (api) {
                try {
                    String reply = ApiAgent.ping();
                    r.append("API: ответ модели «").append(reply == null ? "" : reply.trim()).append("».");
                } catch (IOException ex) {
                    r.append("API: ошибка — ").append(ex.getMessage());
                }
            } else {
                String exe = ClaudeEnv.claudeExecutable();
                try {
                    Process p = new ProcessBuilder(exe, "--version").redirectErrorStream(true).start();
                    String v = new String(p.getInputStream().readAllBytes()).trim();
                    p.waitFor();
                    r.append("Claude Code: ").append(v).append(" (").append(exe).append(").");
                } catch (IOException | InterruptedException ex) {
                    r.append("Claude Code не найден: ").append(ex.getMessage());
                }
            }
            String res = r.toString();
            ui(() -> { if (!connStatus.isDisposed()) { connStatus.setText(res); connStatus.getParent().layout(); } });
        }, "archi-ai-test");
        t.setDaemon(true);
        t.start();
    }

    // ================= ClaudeProcess.Listener (background threads) =================

    @Override
    public void onText(String text) {
        ui(() -> append(Kind.CLAUDE, text));
    }

    @Override
    public void onTool(String name, String detail) {
        String n = name;
        java.util.regex.Matcher m = name == null ? null : java.util.regex.Pattern.compile("^mcp__([A-Za-z0-9-]+)__(.+)$").matcher(name);
        if (m != null && m.matches()) n = ("archi".equals(m.group(1)) ? "Archi" : m.group(1)) + " · " + m.group(2);
        final String label = n;
        ui(() -> append(Kind.TOOL, "→ " + label +(detail.isEmpty() ? "" : " (" + detail + ")")));
    }

    @Override
    public void onToolError(String text) {
        ui(() -> append(Kind.ERROR, "Ошибка инструмента: " + text));
    }

    @Override
    public void onSystem(String text) {
        ui(() -> append(Kind.SYSTEM, text));
    }

    @Override
    public void onTurnDone(boolean error, String info) {
        ui(() -> {
            setBusy(false);
            setStatus(error ? "Ошибка" : "Готово · " + info);
            if (error) append(Kind.ERROR, "Ответ завершился с ошибкой: " + info);
        });
    }

    @Override
    public void onExit(int code, String stderrTail) {
        ui(() -> {
            if (busy) {
                setBusy(false);
                String tail = stderrTail == null ? "" : stderrTail.trim();
                append(Kind.ERROR, "Claude Code завершился (код " + code + ")" + (tail.isEmpty() ? "." : ": " + tail));
                setStatus("Процесс завершён");
            }
        });
    }

    private void ui(Runnable r) {
        Display d = output == null || output.isDisposed() ? null : output.getDisplay();
        if (d != null) d.asyncExec(() -> { if (!output.isDisposed()) r.run(); });
    }

    private void append(Kind kind, String text) {
        if (text == null || text.isEmpty() || output == null || output.isDisposed()) return;
        StringBuilder chunk = new StringBuilder();
        boolean newBlock = kind != lastKind || kind != Kind.CLAUDE;
        if (newBlock && output.getCharCount() > 0) chunk.append(kind == Kind.TOOL && lastKind == Kind.TOOL ? "\n" : "\n\n");
        int labelLen = 0;
        if (newBlock && kind == Kind.USER) { chunk.append("Вы: "); labelLen = 4; }
        if (newBlock && kind == Kind.CLAUDE) { chunk.append("ИИ: "); labelLen = 4; }
        int start = output.getCharCount() + chunk.length() - labelLen;
        chunk.append(text);
        output.append(chunk.toString());

        Display d = output.getDisplay();
        StyleRange r = new StyleRange();
        r.start = start;
        r.length = output.getCharCount() - start;
        switch (kind) {
            case USER -> r.fontStyle = SWT.BOLD;
            case TOOL -> { r.foreground = d.getSystemColor(SWT.COLOR_DARK_GRAY); r.fontStyle = SWT.ITALIC; }
            case SYSTEM -> r.foreground = d.getSystemColor(SWT.COLOR_DARK_GRAY);
            case ERROR -> r.foreground = d.getSystemColor(SWT.COLOR_DARK_RED);
            case CLAUDE -> {
                if (labelLen > 0) {
                    output.setStyleRange(new StyleRange(start, labelLen, d.getSystemColor(SWT.COLOR_DARK_BLUE), null, SWT.BOLD));
                }
                r = null;
            }
        }
        if (r != null && r.length > 0) output.setStyleRange(r);
        lastKind = kind;
        output.setTopIndex(output.getLineCount() - 1);
    }

    @Override
    public void setFocus() {
        input.setFocus();
    }

    @Override
    public void dispose() {
        getSite().getPage().removePostSelectionListener(selectionListener);
        if (rulesDirty && rulesText != null && !rulesText.isDisposed()) saveRules();
        if (backend != null) backend.close();
        super.dispose();
    }
}
