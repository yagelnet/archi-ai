package local.archi.ai;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.gef.EditPart;
import org.eclipse.jface.viewers.IStructuredSelection;

import com.archimatetool.editor.model.IEditorModelManager;
import com.archimatetool.model.IArchimateConcept;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IDiagramModel;
import com.archimatetool.model.IDiagramModelArchimateComponent;

/** Turns the user's Archi selection into a short text context for Claude. */
final class ArchiContext {
    private static final int MAX_ITEMS = 40;

    private ArchiContext() {}

    static String describe(IStructuredSelection sel, IArchimateModel active) {
        List<String> items = new ArrayList<>();
        if (sel != null) {
            for (Object o : sel.toList()) {
                if (items.size() >= MAX_ITEMS) { items.add("…"); break; }
                Object m = (o instanceof EditPart ep) ? ep.getModel() : o;
                if (m instanceof IDiagramModelArchimateComponent dmc) m = dmc.getArchimateConcept();
                if (m instanceof IArchimateConcept c) {
                    items.add(c.eClass().getName() + " «" + c.getName() + "» (id " + c.getId() + ")");
                } else if (m instanceof IDiagramModel d) {
                    items.add("View «" + d.getName() + "» (id " + d.getId() + ")");
                } else if (m instanceof IArchimateModel am) {
                    items.add("Model «" + am.getName() + "»");
                }
            }
        }
        List<String> open = new ArrayList<>();
        for (IArchimateModel am : IEditorModelManager.INSTANCE.getModels()) open.add(am.getName());

        StringBuilder b = new StringBuilder("[Контекст Archi] ");
        if (active != null) {
            b.append("Активная модель MCP: «").append(active.getName()).append("»");
            if (active.getFile() != null) b.append(" (").append(active.getFile().getAbsolutePath()).append(")");
            b.append(". ");
        }
        b.append("Открытые модели: ").append(String.join(", ", open)).append(". ");
        if (items.isEmpty()) b.append("Выделения нет.");
        else b.append("Выделено: ").append(String.join("; ", items)).append('.');
        return b.toString();
    }
}
