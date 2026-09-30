package local.archi.ai;

import java.lang.ref.WeakReference;

import org.eclipse.gef.EditPart;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchPage;

import com.archimatetool.editor.diagram.IDiagramModelEditor;
import com.archimatetool.editor.model.IEditorModelManager;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IArchimateModelObject;
import com.archimatetool.model.IDiagramModel;

/**
 * Keeps the Archi MCP server pointed at the model the user is working with.
 * The MCP server switches its active model only on the "model opened/loaded" event,
 * so we re-announce the chosen model with PROPERTY_MODEL_LOADED. Nothing else in Archi
 * reacts to that event for an already loaded model, so the file is not reopened.
 */
final class ModelSync {
    private static WeakReference<IArchimateModel> announced = new WeakReference<>(null);

    private ModelSync() {}

    /** Model of a selected object: tree element, folder, view, diagram object or the model itself. */
    static IArchimateModel modelOf(Object o) {
        Object m = (o instanceof EditPart ep) ? ep.getModel() : o;
        if (m instanceof IArchimateModel am) return am;
        if (m instanceof IArchimateModelObject mo) return mo.getArchimateModel();
        return null;
    }

    /** The model to work with: selection first, then the active diagram editor, then the only open model. */
    static IArchimateModel target(IStructuredSelection sel, IWorkbenchPage page) {
        if (sel != null) {
            for (Object o : sel.toList()) {
                IArchimateModel m = modelOf(o);
                if (m != null) return m;
            }
        }
        if (page != null) {
            IEditorPart ed = page.getActiveEditor();
            if (ed instanceof IDiagramModelEditor dme) {
                IDiagramModel dm = dme.getModel();
                if (dm != null && dm.getArchimateModel() != null) return dm.getArchimateModel();
            }
        }
        var models = IEditorModelManager.INSTANCE.getModels();
        return models.size() == 1 ? models.get(0) : null;
    }

    /**
     * Makes {@code model} the MCP server's active model.
     * @param force announce even if this model was the last one announced
     * @return true if an announcement was sent
     */
    static synchronized boolean activate(IArchimateModel model, boolean force) {
        if (model == null) return false;
        if (!force && announced.get() == model) return false;
        IEditorModelManager.INSTANCE.firePropertyChange(IEditorModelManager.INSTANCE,
                IEditorModelManager.PROPERTY_MODEL_LOADED, null, model);
        announced = new WeakReference<>(model);
        return true;
    }

    static IArchimateModel lastAnnounced() {
        return announced.get();
    }
}
