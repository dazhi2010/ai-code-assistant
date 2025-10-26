package cn.com.wewell.aicodeassistant.util;

import cn.com.wewell.aicodeassistant.service.PromptManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;

import java.io.IOException;
import java.util.List;

public final class PromptContentUtil {

    private static final long MAX_FILE_SIZE = 5 * 1024 * 1024; // 5MB

    private PromptContentUtil() {}

    public static void addFilesToPrompt(Project project, List<VirtualFile> files, boolean clearBefore, boolean ensureMyNeedSection) {
        if (project == null || files == null || files.isEmpty()) return;

        String block = buildFilesMarkdown(project, files);
        if (block.isEmpty()) return;

        PromptManager pm = PromptManager.getInstance(project);
        String result = block;

        if (ensureMyNeedSection) {
            String current = clearBefore ? "" : pm.getInputContent();
            if (!current.contains("## 我的需求")) {
                StringBuilder sb = new StringBuilder(result);
                if (!result.endsWith("\n\n")) sb.append('\n');
                sb.append("## 我的需求\n[请在这里补充您的需求描述...]");
                result = sb.toString();
            }
        }

        if (clearBefore) {
            pm.setInputContentAndNotify(result);
        } else {
            pm.addText(result);
        }
    }

    public static String buildFilesMarkdown(Project project, List<VirtualFile> files) {
        StringBuilder sb = new StringBuilder();
        for (VirtualFile file : files) {
            if (file == null || file.isDirectory() || !isProcessable(file)) continue;
            try {
                String relativePath = VfsUtil.getRelativePath(file, project.getBaseDir(), '/');
                String content = VfsUtilCore.loadText(file);

                sb.append("## 文件路径: ").append(relativePath).append("\n");
                sb.append("```").append(getFileTypeMarkdown(file)).append("\n");
                sb.append(content);
                sb.append("\n```").append("\n\n");
            } catch (IOException ignored) {
            }
        }
        return sb.toString();
    }

    public static boolean isProcessable(VirtualFile file) {
        return !file.getFileType().isBinary() && file.getLength() < MAX_FILE_SIZE;
    }

    public static String getFileTypeMarkdown(VirtualFile file) {
        String name = file.getFileType().getName().toLowerCase();
        if (name.contains("java")) return "java";
        if (name.contains("javascript")) return "javascript";
        if (name.contains("typescript")) return "typescript";
        if (name.contains("python")) return "python";
        if (name.contains("html")) return "html";
        if (name.contains("css")) return "css";
        if (name.contains("xml")) return "xml";
        if (name.contains("json")) return "json";
        if (name.contains("markdown")) return "markdown";
        return "";
    }
}