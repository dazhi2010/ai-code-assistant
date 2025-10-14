package cn.com.wewell.aicodeassistant.action.tree;

import cn.com.wewell.aicodeassistant.model.AiResponseAction;
import cn.com.wewell.aicodeassistant.service.ChangeApplierService;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public class ApplyAllChangesAction extends AnAction {
    private final List<AiResponseAction> allActions;

    public ApplyAllChangesAction(List<AiResponseAction> allActions) {
        super("应用全部变更");
        this.allActions = allActions;
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) return;
        ChangeApplierService.getInstance(project).applyChanges(allActions);
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        e.getPresentation().setEnabled(allActions != null && !allActions.isEmpty());
    }
}