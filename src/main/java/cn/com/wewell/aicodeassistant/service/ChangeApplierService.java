package cn.com.wewell.aicodeassistant.service;

import cn.com.wewell.aicodeassistant.common.Constants;
import cn.com.wewell.aicodeassistant.model.AiResponseAction;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.command.CommandProcessor;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.codeStyle.CodeStyleManager;

import java.io.File;
import java.io.IOException;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Service(Service.Level.PROJECT)
public final class ChangeApplierService {

    private final Project project;

    public ChangeApplierService(Project project) {
        this.project = project;
    }

    public static ChangeApplierService getInstance(Project project) {
        return project.getService(ChangeApplierService.class);
    }

    public void applyChanges(List<AiResponseAction> actions) {
        Map<String, List<AiResponseAction>> groupedActions = actions.stream()
                .collect(Collectors.groupingBy(AiResponseAction::filePath));

        CommandProcessor.getInstance().executeCommand(project, () -> {
            for (Map.Entry<String, List<AiResponseAction>> entry : groupedActions.entrySet()) {
                String filePath = entry.getKey();
                List<AiResponseAction> fileActions = entry.getValue();

                Optional<AiResponseAction> overwriteAction = fileActions.stream()
                        .filter(a -> "OVERWRITE".equalsIgnoreCase(a.action()))
                        .findFirst();

                if (overwriteAction.isPresent()) {
                    applyOverwrite(overwriteAction.get());
                    continue;
                }

                fileActions.sort(Comparator.comparingInt(this::getActionStartLine));

                int lineOffset = 0;

                for (AiResponseAction action : fileActions) {
                    int linesChanged = applyActionWithOffset(action, lineOffset);
                    lineOffset += linesChanged;
                }
            }
            notifySuccess("已成功应用 " + actions.size() + " 个变更。");
        }, "Apply AI Assistant Changes", null);
    }

    public int applyActionWithOffset(AiResponseAction originalAction, int lineOffset) {
        AiResponseAction adjustedAction = new AiResponseAction(
                originalAction.action(),
                originalAction.filePath(),
                originalAction.startLine() != null ? originalAction.startLine() + lineOffset : null,
                originalAction.endLine() != null ? originalAction.endLine() + lineOffset : null,
                originalAction.line() != null ? originalAction.line() + lineOffset : null,
                originalAction.content(),
                originalAction.searchText(),
                originalAction.replaceText()
        );

        try {
            return switch (adjustedAction.action().toUpperCase()) {
                case "CREATE" -> applyCreate(adjustedAction);
                case "UPDATE" -> applyUpdate(adjustedAction);
                case "INSERT" -> applyInsert(adjustedAction);
                case "DELETE" -> applyDelete(adjustedAction);
                default -> {
                    notifyWarning("未知的操作类型: " + adjustedAction.action());
                    yield 0;
                }
            };
        } catch (IOException e) {
            notifyError("应用变更失败: " + e.getMessage());
            return 0;
        }
    }

    private int applyCreate(AiResponseAction action) throws IOException {
        WriteCommandAction.runWriteCommandAction(project, () -> {
            String projectBasePath = project.getBasePath();
            if (projectBasePath == null) return;

            String correctedRelativePath = action.filePath().replace('/', File.separatorChar);
            File targetFile = new File(projectBasePath, correctedRelativePath);
            File parentDir = targetFile.getParentFile();
            if (parentDir == null) return;

            try {
                VirtualFile parentVirtualDir = VfsUtil.createDirectories(parentDir.getAbsolutePath());
                VirtualFile newFile = parentVirtualDir.createChildData(this, targetFile.getName());
                newFile.setBinaryContent(action.content().getBytes());
                formatFile(newFile);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
        return 0;
    }

    private void applyOverwrite(AiResponseAction action) {
        WriteCommandAction.runWriteCommandAction(project, () -> {
            Document document = getDocument(action.filePath());
            if (document == null) {
                notifyWarning("文件未找到，无法覆盖: " + action.filePath());
                return;
            }
            document.replaceString(0, document.getTextLength(), action.content());
            FileDocumentManager.getInstance().saveDocument(document);
            formatFile(FileDocumentManager.getInstance().getFile(document));
        });
    }

    private int applyUpdate(AiResponseAction action) {
        final int[] linesChanged = {0};
        WriteCommandAction.runWriteCommandAction(project, () -> {
            Document document = getDocument(action.filePath());
            if (document == null) return;

            int startLine = action.startLine() - 1;
            int endLine = action.endLine() - 1;
            if (startLine < 0 || endLine >= document.getLineCount() || startLine > endLine) return;

            // --- 核心修复：将 UPDATE 拆分为 DELETE + INSERT ---
            int startOffset = document.getLineStartOffset(startLine);
            // 注意：删除时要包含最后一行的换行符，所以偏移量要到下一行的行首
            int endOffset = (endLine + 1 < document.getLineCount()) ? document.getLineStartOffset(endLine + 1) : document.getTextLength();

            document.deleteString(startOffset, endOffset);

            String content = action.content();
            // 确保插入的内容有换行符，除非它本身就是空的
            if (!content.isEmpty() && !content.endsWith("\n")) {
                content += "\n";
            }
            document.insertString(startOffset, content);

            FileDocumentManager.getInstance().saveDocument(document);
            formatFile(FileDocumentManager.getInstance().getFile(document));

            int originalLineCount = endLine - startLine + 1;
            int newLineCount = countNewlines(content);
            linesChanged[0] = newLineCount - originalLineCount;
        });
        return linesChanged[0];
    }

    private int applyInsert(AiResponseAction action) {
        final int[] linesChanged = {0};
        WriteCommandAction.runWriteCommandAction(project, () -> {
            Document document = getDocument(action.filePath());
            if (document == null) return;

            int line = action.line() - 1;
            if (line < 0 || line > document.getLineCount()) return;

            int offset = document.getLineStartOffset(line);
            String contentToInsert = action.content().endsWith("\n") ? action.content() : action.content() + "\n";
            document.insertString(offset, contentToInsert);
            FileDocumentManager.getInstance().saveDocument(document);
            formatFile(FileDocumentManager.getInstance().getFile(document));

            linesChanged[0] = countNewlines(contentToInsert);
        });
        return linesChanged[0];
    }

    private int applyDelete(AiResponseAction action) throws IOException {
        final int[] linesChanged = {0};
        WriteCommandAction.runWriteCommandAction(project, () -> {
            VirtualFile file = findVirtualFile(action.filePath());
            if (file == null) return;

            try {
                if (action.startLine() != null && action.endLine() != null) {
                    Document document = getDocument(action.filePath());
                    if (document == null) return;

                    int startLine = action.startLine() - 1;
                    int endLine = action.endLine() - 1;
                    if (startLine < 0 || endLine >= document.getLineCount() || startLine > endLine) return;

                    int startOffset = document.getLineStartOffset(startLine);
                    int endOffset = (endLine + 1 < document.getLineCount()) ? document.getLineStartOffset(endLine + 1) : document.getTextLength();
                    document.deleteString(startOffset, endOffset);
                    FileDocumentManager.getInstance().saveDocument(document);

                    linesChanged[0] = -(endLine - startLine + 1);
                } else {
                    file.delete(this);
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
        return linesChanged[0];
    }

    public int getActionStartLine(AiResponseAction action) {
        if (action.startLine() != null) return action.startLine();
        if (action.line() != null) return action.line();
        return 0;
    }

    private int countNewlines(String str) {
        if (str == null || str.isEmpty()) return 0;
        // 如果字符串不以换行符结尾，那么它的行数是换行符数量+1。如果以换行符结尾，则是换行符数量。
        int count = (int) str.chars().filter(ch -> ch == '\n').count();
        return str.endsWith("\n") ? count : count + 1;
    }

    public VirtualFile findVirtualFile(String relativePath) {
        String projectBasePath = project.getBasePath();
        if (projectBasePath == null) return null;

        // 将相对路径转换为绝对路径
        String fullPath = new File(projectBasePath, relativePath.replace('/', File.separatorChar)).getAbsolutePath();

        // 优先使用 refreshAndFindFileByPath，因为它能更好地处理文件系统与VFS的同步问题
        VirtualFile file = LocalFileSystem.getInstance().refreshAndFindFileByPath(fullPath);

        // 如果找不到，作为后备，尝试使用 findFileByIoFile
        if (file == null) {
            File targetFile = new File(fullPath);
            file = LocalFileSystem.getInstance().findFileByIoFile(targetFile);
        }

        return file;
    }

    private Document getDocument(String relativePath) {
        VirtualFile file = findVirtualFile(relativePath);
        if (file == null) {
            return null;
        }
        return FileDocumentManager.getInstance().getDocument(file);
    }

    private void formatFile(VirtualFile file) {
        if (file == null) return;
        PsiFile psiFile = PsiManager.getInstance(project).findFile(file);
        if (psiFile != null) {
            CodeStyleManager.getInstance(project).reformat(psiFile);
        }
    }

    private void notifySuccess(String message) {
        NotificationGroupManager.getInstance().getNotificationGroup(Constants.NOTIFICATION_GROUP_ID)
                .createNotification(message, NotificationType.INFORMATION).notify(project);
    }

    private void notifyWarning(String message) {
        NotificationGroupManager.getInstance().getNotificationGroup(Constants.NOTIFICATION_GROUP_ID)
                .createNotification(message, NotificationType.WARNING).notify(project);
    }

    private void notifyError(String message) {
        NotificationGroupManager.getInstance().getNotificationGroup(Constants.NOTIFICATION_GROUP_ID)
                .createNotification(message, NotificationType.ERROR).notify(project);
    }
}