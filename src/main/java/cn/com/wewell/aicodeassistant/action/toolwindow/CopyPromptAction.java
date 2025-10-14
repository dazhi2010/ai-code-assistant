package cn.com.wewell.aicodeassistant.action.toolwindow;

import cn.com.wewell.aicodeassistant.common.Constants;
import cn.com.wewell.aicodeassistant.service.PromptManager;
import com.intellij.icons.AllIcons;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

import java.awt.datatransfer.StringSelection;

public class CopyPromptAction extends AnAction implements DumbAware {
    public CopyPromptAction() { super("复制", "复制输入区内容到剪贴板", AllIcons.Actions.Copy); }
    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) return;
        String content = PromptManager.getInstance(project).getInputContent();
        CopyPasteManager.getInstance().setContents(new StringSelection(content));
        NotificationGroupManager.getInstance().getNotificationGroup(Constants.NOTIFICATION_GROUP_ID)
                .createNotification("提示词已复制到剪贴板", NotificationType.INFORMATION).notify(project);
    }
}