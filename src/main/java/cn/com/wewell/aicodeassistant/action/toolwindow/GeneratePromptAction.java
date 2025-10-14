package cn.com.wewell.aicodeassistant.action.toolwindow;

import cn.com.wewell.aicodeassistant.service.PromptManager;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

public class GeneratePromptAction extends AnAction implements DumbAware {
    public GeneratePromptAction() { super("生成提示词", "将当前内容包装成完整的AI提示词", AllIcons.Actions.Execute); }
    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) return;
        PromptManager.getInstance(project).generateFullPrompt();
    }
}