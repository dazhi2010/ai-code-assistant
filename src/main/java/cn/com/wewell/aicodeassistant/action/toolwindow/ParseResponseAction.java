package cn.com.wewell.aicodeassistant.action.toolwindow;

import cn.com.wewell.aicodeassistant.common.Constants;
import cn.com.wewell.aicodeassistant.model.AiResponseAction;
import cn.com.wewell.aicodeassistant.service.PromptManager;
import cn.com.wewell.aicodeassistant.ui.AssistantToolWindow;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.PlatformDataKeys;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.stream.Collectors;

public class ParseResponseAction extends AnAction implements DumbAware {
    public ParseResponseAction() {
        super("解析", "解析AI响应并预览变更", com.intellij.icons.AllIcons.Actions.Find);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) return;

        ToolWindow toolWindow = e.getData(PlatformDataKeys.TOOL_WINDOW);
        if (toolWindow == null || !(toolWindow.getContentManager().getContent(0).getComponent() instanceof AssistantToolWindow)) {
            return;
        }
        AssistantToolWindow assistantToolWindow = (AssistantToolWindow) toolWindow.getContentManager().getContent(0).getComponent();

        PromptManager promptManager = PromptManager.getInstance(project);
        boolean success = promptManager.parseResponse();

        if (success) {
            List<AiResponseAction> actions = promptManager.getParsedActions();
            assistantToolWindow.showChangesPreview(actions.stream().collect(Collectors.groupingBy(AiResponseAction::filePath)));
            NotificationGroupManager.getInstance().getNotificationGroup(Constants.NOTIFICATION_GROUP_ID)
                    .createNotification("JSON解析成功，请在右侧面板预览变更", NotificationType.INFORMATION).notify(project);
        } else {
            assistantToolWindow.showJsonInputView();
            NotificationGroupManager.getInstance().getNotificationGroup(Constants.NOTIFICATION_GROUP_ID)
                    .createNotification("JSON解析失败，请检查格式", NotificationType.ERROR).notify(project);
        }
    }
}