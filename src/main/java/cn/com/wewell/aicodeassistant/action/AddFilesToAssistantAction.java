package cn.com.wewell.aicodeassistant.action;

import cn.com.wewell.aicodeassistant.service.AssistantConfigService;
import cn.com.wewell.aicodeassistant.service.PromptManager;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileFilter;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 中文注释：
 * 1) 依据 .ai-assistant/config.json 的 ignore 规则过滤文件/目录（支持名称、目录、* 号等通配）。
 * 2) 将选中文件内容追加到工作区后，如末尾没有“## 我的需求”段，则自动追加该段。
 */
public class AddFilesToAssistantAction extends AnAction {

    private static final long MAX_FILE_SIZE = 5 * 1024 * 1024; // 5 MB

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) {
            return;
        }

        VirtualFile[] selectedFiles = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY);
        if (selectedFiles == null || selectedFiles.length == 0) {
            return;
        }

        PromptManager promptManager = PromptManager.getInstance(project);
        List<VirtualFile> filesToAdd = new ArrayList<>();

        // 异步执行文件遍历和读取
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            AssistantConfigService configService = AssistantConfigService.getInstance(project);
            // 中文注释：确保默认配置文件存在
            configService.ensureDefaultConfig();

            for (VirtualFile fileOrDir : selectedFiles) {
                if (fileOrDir.isDirectory()) {
                    // 中文注释：通过过滤器避免深入被忽略的目录
                    VirtualFileFilter dirFilter = vf -> !vf.isDirectory() || !configService.isIgnored(vf);
                    VfsUtilCore.iterateChildrenRecursively(fileOrDir, dirFilter, vf -> {
                        if (!vf.isDirectory() && isProcessable(vf) && !configService.isIgnored(vf)) {
                            filesToAdd.add(vf);
                        }
                        return true;
                    });
                } else {
                    if (isProcessable(fileOrDir) && !configService.isIgnored(fileOrDir)) {
                        filesToAdd.add(fileOrDir);
                    }
                }
            }

            // 在UI线程中构建字符串并更新UI
            ApplicationManager.getApplication().invokeLater(() -> {
                if (!filesToAdd.isEmpty()) {
                    // 追加到现有内容末尾，并确保“## 我的需求”存在
                    cn.com.wewell.aicodeassistant.util.PromptContentUtil.addFilesToPrompt(project, filesToAdd, false, true);
                }
            });
        });
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        VirtualFile[] files = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY);
        e.getPresentation().setEnabledAndVisible(project != null && files != null && files.length > 0);
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    private boolean isProcessable(VirtualFile file) {
        return !file.getFileType().isBinary() && file.getLength() < MAX_FILE_SIZE;
    }

    private String getFileTypeMarkdown(VirtualFile file) {
        FileType fileType = file.getFileType();
        String name = fileType.getName().toLowerCase();
        // 中文注释：简单的映射，可以根据需要扩展
        if (name.contains("java")) return "java";
        if (name.contains("javascript")) return "javascript";
        if (name.contains("typescript")) return "typescript";
        if (name.contains("python")) return "python";
        if (name.contains("html")) return "html";
        if (name.contains("css")) return "css";
        if (name.contains("xml")) return "xml";
        if (name.contains("json")) return "json";
        if (name.contains("markdown")) return "markdown";
        return ""; // 默认为纯文本
    }
}
