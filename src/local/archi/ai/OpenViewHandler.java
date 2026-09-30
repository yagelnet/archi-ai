package local.archi.ai;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.handlers.HandlerUtil;

/** Menu Claude → «Панель Claude Code». */
public class OpenViewHandler extends AbstractHandler {
    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        IWorkbenchPage page = HandlerUtil.getActiveWorkbenchWindowChecked(event).getActivePage();
        try {
            page.showView(ClaudeView.ID);
        } catch (PartInitException e) {
            throw new ExecutionException("Не удалось открыть панель Claude Code", e);
        }
        return null;
    }
}
