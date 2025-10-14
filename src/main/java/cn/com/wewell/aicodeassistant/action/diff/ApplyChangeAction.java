package cn.com.wewell.aicodeassistant.action.diff;

import cn.com.wewell.aicodeassistant.common.Constants;
import cn.com.wewell.aicodeassistant.model.AiResponseAction;
import cn.com.wewell.aicodeassistant.service.ChangeApplierService;
import cn.com.wewell.aicodeassistant.service.HistoryManager;
import cn.com.wewell.aicodeassistant.service.PromptManager;
import cn.com.wewell.aicodeassistant.ui.AssistantToolWindow;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import org.jetbrains.annotations.NotNull;

import java.util.List;

// 修复：使用 implements 而不是注解
public class ApplyChangeAction extends AnAction implements DumbAware {

    private final List<AiResponseAction> actionsToApply;

    public ApplyChangeAction(List<AiResponseAction> actions) {
        super("应用全部变更", "将此文件的所有变更应用到项目中", AllIcons.Actions.Execute);
        this.actionsToApply = actions;
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null || actionsToApply == null || actionsToApply.isEmpty()) return;

        // 1. 应用变更
        ChangeApplierService applier = ChangeApplierService.getInstance(project);
        applier.applyChanges(actionsToApply);

        // 2. 强制刷新文件
        VirtualFile file = applier.findVirtualFile(actionsToApply.get(0).filePath());
        if (file != null) {
            // 在UI线程之外刷新
            file.refresh(true, false);
        }

        // 3. 保存历史记录
        PromptManager promptManager = PromptManager.getInstance(project);
        String request = promptManager.getInputContent();
        String response = promptManager.getOutputContent();

        // 从 ToolWindowManager 获取我们的工具窗口实例
        ToolWindow toolWindow = ToolWindowManager.getInstance(project).getToolWindow(Constants.TOOL_WINDOW_ID);
        if (toolWindow != null) {
            // 从 ToolWindow 的内容面板获取我们的自定义UI类实例
            if (toolWindow.getContentManager().getContent(0).getComponent() instanceof AssistantToolWindow) {
                AssistantToolWindow assistantToolWindow = (AssistantToolWindow) toolWindow.getContentManager().getContent(0).getComponent();
                String theme = assistantToolWindow.getTheme();
                HistoryManager.getInstance(project).saveHistory(theme, request, response);
            }
        }
    }
}