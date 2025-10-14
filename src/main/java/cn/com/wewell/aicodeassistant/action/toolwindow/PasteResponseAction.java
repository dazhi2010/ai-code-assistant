package cn.com.wewell.aicodeassistant.action.toolwindow;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.DumbAware;
import org.jetbrains.annotations.NotNull;
import cn.com.wewell.aicodeassistant.service.PromptManager;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.project.Project;
import java.awt.datatransfer.DataFlavor;

public class PasteResponseAction extends AnAction implements DumbAware {
    public PasteResponseAction() { super("粘贴", "从剪贴板粘贴AI响应", AllIcons.Actions.MenuPaste); }
    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) return;
        String content = CopyPasteManager.getInstance().getContents(DataFlavor.stringFlavor);
        if (content != null) {
            PromptManager.getInstance(project).setOutputContentAndNotify(content);
        }
    }
}