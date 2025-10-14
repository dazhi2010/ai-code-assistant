package cn.com.wewell.aicodeassistant.action.toolwindow;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.DumbAware;
import org.jetbrains.annotations.NotNull;
import cn.com.wewell.aicodeassistant.service.PromptManager;
import com.intellij.openapi.project.Project;

public class ClearAllAction extends AnAction implements DumbAware {
    public ClearAllAction() { super("清空", "清空所有内容", AllIcons.Actions.GC); }
    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) return;
        PromptManager.getInstance(project).clearAll();
    }
}