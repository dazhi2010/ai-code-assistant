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

        com.intellij.openapi.progress.ProgressManager.getInstance().run(new com.intellij.openapi.progress.Task.Backgroundable(project, "正在解析 AI 响应...", true) {
            private boolean success = false;
            private List<AiResponseAction> actions;

            @Override
            public void run(@NotNull com.intellij.openapi.progress.ProgressIndicator indicator) {
                indicator.setIndeterminate(true);
                PromptManager promptManager = PromptManager.getInstance(project);
                
                // 使用 ReadAction 包裹，因为 parseResponse 内部会调用 checkMatchStatus 读取文件 Document
                success = com.intellij.openapi.application.ReadAction.compute(() -> {
                    try {
                        return promptManager.parseResponse();
                    } catch (com.intellij.openapi.progress.ProcessCanceledException ex) {
                        return false;
                    }
                });
                
                if (success) {
                    actions = promptManager.getParsedActions();
                }
            }

            @Override
            public void onSuccess() {
                com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater(() -> {
                    if (project.isDisposed()) return;
                    
                    if (success && actions != null) {
                        PromptManager promptManager = PromptManager.getInstance(project);
                        assistantToolWindow.showChangesPreview(actions.stream().collect(Collectors.groupingBy(AiResponseAction::filePath)));

                        String msg = (actions.isEmpty() && promptManager.getRequiredFiles().isEmpty() && promptManager.getRequiredClasses().isEmpty())
                                ? "解析成功，已在右侧面板显示对话内容"
                                : "解析成功，请在右侧面板预览变更";

                        NotificationGroupManager.getInstance().getNotificationGroup(Constants.NOTIFICATION_GROUP_ID)
                                .createNotification(msg, NotificationType.INFORMATION).notify(project);
                    } else {
                        assistantToolWindow.showJsonInputView();
                        NotificationGroupManager.getInstance().getNotificationGroup(Constants.NOTIFICATION_GROUP_ID)
                                .createNotification("解析未完成或被取消", NotificationType.WARNING).notify(project);
                    }
                });
            }

            @Override
            public void onCancel() {
                com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater(() -> {
                    assistantToolWindow.showJsonInputView();
                    NotificationGroupManager.getInstance().getNotificationGroup(Constants.NOTIFICATION_GROUP_ID)
                            .createNotification("解析已取消", NotificationType.WARNING).notify(project);
                });
            }
        });
    }}