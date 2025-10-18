package cn.com.wewell.aicodeassistant.action.toolwindow;

import cn.com.wewell.aicodeassistant.common.Constants;
import cn.com.wewell.aicodeassistant.service.AssistantConfigService;
import com.intellij.icons.AllIcons;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

/**
 * 中文注释：手动刷新 .ai-assistant/config.json 的配置，使忽略规则即时生效。
 */
public class RefreshAssistantConfigAction extends AnAction implements DumbAware {
    public RefreshAssistantConfigAction() {
        super("刷新配置", "重新加载 .ai-assistant/config.json 配置", AllIcons.Actions.Refresh);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) return;
        AssistantConfigService.getInstance(project).reload();
        NotificationGroupManager.getInstance().getNotificationGroup(Constants.NOTIFICATION_GROUP_ID)
                .createNotification("配置已刷新", NotificationType.INFORMATION).notify(project);
    }
}
