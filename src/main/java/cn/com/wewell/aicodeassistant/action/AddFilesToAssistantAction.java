package cn.com.wewell.aicodeassistant.action;

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
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * @author yuqf
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
            for (VirtualFile fileOrDir : selectedFiles) {
                if (fileOrDir.isDirectory()) {
                    VfsUtilCore.iterateChildrenRecursively(fileOrDir, null, file -> {
                        if (!file.isDirectory() && isProcessable(file)) {
                            filesToAdd.add(file);
                        }
                        return true;
                    });
                } else {
                    if (isProcessable(fileOrDir)) {
                        filesToAdd.add(fileOrDir);
                    }
                }
            }

            // 在UI线程中构建字符串并更新UI
            ApplicationManager.getApplication().invokeLater(() -> {
                StringBuilder sb = new StringBuilder();
                for (VirtualFile file : filesToAdd) {
                    try {
                        String relativePath = VfsUtil.getRelativePath(file, project.getBaseDir(), '/');
                        String content = VfsUtilCore.loadText(file);

                        sb.append("## 文件路径: ").append(relativePath).append("\n");
                        sb.append("```").append(getFileTypeMarkdown(file)).append("\n");
                        sb.append(content);
                        sb.append("\n```\n\n");

                    } catch (IOException ex) {
                        // Ignore files that cannot be read
                    }
                }
                promptManager.addText(sb.toString());
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
        // 简单的映射，可以根据需要扩展
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
