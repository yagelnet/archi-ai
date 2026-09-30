package local.archi.ai;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Properties;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.handlers.HandlerUtil;

import com.archimatetool.model.IArchimateModel;

/**
 * Menu Claude → «Claude Code в терминале»: opens the full interactive Claude Code
 * (slash commands, permission prompts) in a console window, connected to the Archi MCP server
 * and pointed at the model the user is working with.
 */
public class OpenTerminalHandler extends AbstractHandler {
    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        try {
            IWorkbenchWindow win = HandlerUtil.getActiveWorkbenchWindow(event);
            ISelection sel = HandlerUtil.getCurrentSelection(event);
            IArchimateModel model = ModelSync.target(sel instanceof IStructuredSelection ss ? ss : null,
                    win != null ? win.getActivePage() : null);
            if (model == null) model = ModelSync.lastAnnounced();
            ModelSync.activate(model, true);

            String prompt = ClaudeEnv.systemPrompt();
            if (model != null) prompt += " Активная модель MCP: «" + model.getName() + "».";
            if (rulesEnabled()) prompt += " " + ClaudeEnv.loadRules();
            prompt = prompt.replaceAll("\\s*[\\r\\n]+\\s*", " ").replace("\"", "'");

            File cfg = ClaudeEnv.mcpConfig();
            File dir = ClaudeEnv.workingDir(model);
            List<String> cmd = List.of("cmd.exe", "/c", "start", "Claude Code — Archi", "/D", dir.getAbsolutePath(),
                    "cmd.exe", "/k", ClaudeEnv.claudeExecutable(),
                    "--mcp-config", cfg.getAbsolutePath(),
                    "--append-system-prompt", prompt);
            new ProcessBuilder(cmd).directory(dir).start();
        } catch (IOException e) {
            MessageDialog.openError(HandlerUtil.getActiveShell(event), "Claude Code",
                    "Не удалось открыть терминал с Claude Code: " + e.getMessage());
        }
        return null;
    }

    private static boolean rulesEnabled() {
        File f = new File(ClaudeEnv.rulesFile().getParentFile(), "settings.properties");
        if (!f.isFile()) return true;
        Properties p = new Properties();
        try (InputStream in = new FileInputStream(f)) {
            p.load(in);
        } catch (IOException e) {
            return true;
        }
        return Boolean.parseBoolean(p.getProperty("rules.enabled", "true"));
    }
}
