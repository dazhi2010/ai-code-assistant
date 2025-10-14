package cn.com.wewell.aicodeassistant.ui;

import cn.com.wewell.aicodeassistant.service.PromptManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import org.jetbrains.annotations.NotNull;

public class AssistantToolWindowFactory implements ToolWindowFactory {
    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        AssistantToolWindow assistantToolWindow = new AssistantToolWindow(project);
        ContentFactory contentFactory = ContentFactory.getInstance();
        Content content = contentFactory.createContent(assistantToolWindow, "", false);
        toolWindow.getContentManager().addContent(content);
        // 在工具窗口内容创建后，立即从服务同步一次初始状态。
        // 这可以确保即便是第一次打开，也能正确显示已经存在于服务中的数据。
        PromptManager promptManager = PromptManager.getInstance(project);
        assistantToolWindow.syncInitialState(promptManager.getInputContent(), promptManager.getOutputContent());
    }
}