package cn.com.wewell.aicodeassistant.service;

import cn.com.wewell.aicodeassistant.common.Constants;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VfsUtil;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;

@Service(Service.Level.PROJECT)
public final class HistoryManager {

    private final Project project;
    public static final String HISTORY_DIR = ".ai-assistant/history";

    public HistoryManager(Project project) {
        this.project = project;
    }

    public static HistoryManager getInstance(Project project) {
        return project.getService(HistoryManager.class);
    }

    /**
     * 保存一次完整的交互到历史记录
     * @param theme 主题名称，用于创建目录
     * @param requestMd 请求的Markdown内容
     * @param responseJson AI返回的JSON内容
     */
    public void saveHistory(String theme, String requestMd, String responseJson) {
        ApplicationManager.getApplication().runWriteAction(() -> {
            String projectBasePath = project.getBasePath();
            if (projectBasePath == null) return;

            try {
                // 1. 创建本次主题的目录
                String timestamp = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(new Date());
                // 清理主题名中的非法字符，并处理空主题
                String safeTheme = (theme == null || theme.isBlank()) ? "未命名主题" : theme.replaceAll("[^a-zA-Z0-9\\u4e00-\\u9fa5_-]", "_");
                String dirPath = projectBasePath + "/" + HISTORY_DIR + "/" + timestamp + "_" + safeTheme;
                var themeDir = VfsUtil.createDirectories(dirPath);

                // 2. 保存请求和响应文件
                var requestFile = themeDir.findOrCreateChildData(this, "request.md");
                requestFile.setBinaryContent(requestMd.getBytes(StandardCharsets.UTF_8));

                var responseFile = themeDir.findOrCreateChildData(this, "response.json");
                responseFile.setBinaryContent(responseJson.getBytes(StandardCharsets.UTF_8));

                NotificationGroupManager.getInstance().getNotificationGroup(Constants.NOTIFICATION_GROUP_ID)
                        .createNotification("操作历史已保存", NotificationType.INFORMATION).notify(project);

            } catch (IOException e) {
                NotificationGroupManager.getInstance().getNotificationGroup(Constants.NOTIFICATION_GROUP_ID)
                        .createNotification("历史记录保存失败: " + e.getMessage(), NotificationType.ERROR).notify(project);
                e.printStackTrace();
            }
        });
    }
}