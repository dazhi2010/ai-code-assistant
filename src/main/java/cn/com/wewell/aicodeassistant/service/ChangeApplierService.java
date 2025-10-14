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
import com.intellij.psi.PsiDocumentManager;
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
            // 中文注释：覆盖前确保 Document 已提交，避免 PSI 未提交导致的各种副作用
            PsiDocumentManager pdm = PsiDocumentManager.getInstance(project);
            pdm.doPostponedOperationsAndUnblockDocument(document);
            pdm.commitDocument(document);

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

            PsiDocumentManager pdm = PsiDocumentManager.getInstance(project);
            pdm.doPostponedOperationsAndUnblockDocument(document);
            pdm.commitDocument(document);

            int lineCount = document.getLineCount();

            // 中文注释：对起止行进行“夹取”，避免 AI 提供的行号超出范围导致整个 UPDATE 被跳过（如 application.yml、DEPLOYMENT_REMOTE_NOTES.md）
            int startLine0 = (action.startLine() != null ? action.startLine() - 1 : 0);
            int endLine0 = (action.endLine() != null ? action.endLine() - 1 : startLine0);

            if (lineCount == 0) {
                // 空文件：直接写入目标内容
                String content = ensureTrailingNewline(action.content());
                document.insertString(0, content);
                FileDocumentManager.getInstance().saveDocument(document);
                formatFile(FileDocumentManager.getInstance().getFile(document));
                linesChanged[0] = countNewlines(content);
                return;
            }

            startLine0 = clamp(startLine0, 0, lineCount - 1);
            endLine0 = clamp(Math.max(startLine0, endLine0), startLine0, lineCount - 1);

            int startOffset = document.getLineStartOffset(startLine0);
            int endOffset = (endLine0 + 1 < lineCount) ? document.getLineStartOffset(endLine0 + 1) : document.getTextLength();

            document.deleteString(startOffset, endOffset);

            String content = ensureTrailingNewline(action.content());
            document.insertString(startOffset, content);

            FileDocumentManager.getInstance().saveDocument(document);
            formatFile(FileDocumentManager.getInstance().getFile(document));

            int originalLineCount = endLine0 - startLine0 + 1;
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

            PsiDocumentManager pdm = PsiDocumentManager.getInstance(project);
            pdm.doPostponedOperationsAndUnblockDocument(document);
            pdm.commitDocument(document);

            int lineCount = document.getLineCount();
            int line0 = (action.line() != null ? action.line() - 1 : lineCount);
            // 中文注释：允许在最后一行之后插入（追加场景）
            line0 = clamp(line0, 0, lineCount);

            int offset = (line0 == lineCount) ? document.getTextLength() : document.getLineStartOffset(line0);
            String contentToInsert = ensureTrailingNewline(action.content());
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

                    PsiDocumentManager pdm = PsiDocumentManager.getInstance(project);
                    pdm.doPostponedOperationsAndUnblockDocument(document);
                    pdm.commitDocument(document);

                    int lineCount = document.getLineCount();
                    int start0 = clamp(action.startLine() - 1, 0, Math.max(0, lineCount - 1));
                    int end0 = clamp(action.endLine() - 1, start0, Math.max(0, lineCount - 1));

                    if (lineCount == 0) return;

                    int startOffset = document.getLineStartOffset(start0);
                    int endOffset = (end0 + 1 < lineCount) ? document.getLineStartOffset(end0 + 1) : document.getTextLength();
                    document.deleteString(startOffset, endOffset);
                    FileDocumentManager.getInstance().saveDocument(document);

                    linesChanged[0] = -(end0 - start0 + 1);
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
        int count = (int) str.chars().filter(ch -> ch == '\n').count();
        return str.endsWith("\n") ? count : count + 1;
    }

    private String ensureTrailingNewline(String s) {
        if (s == null) return "";
        return s.endsWith("\n") || s.isEmpty() ? s : (s + "\n");
    }

    public VirtualFile findVirtualFile(String relativePath) {
        String projectBasePath = project.getBasePath();
        if (projectBasePath == null) return null;

        String fullPath = new File(projectBasePath, relativePath.replace('/', File.separatorChar)).getAbsolutePath();
        VirtualFile file = LocalFileSystem.getInstance().refreshAndFindFileByPath(fullPath);
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
        // 中文注释：在 PSI 操作前确保 Document 已提交，避免 Markdown/YAML 等文件触发 PSI 非提交异常
        PsiDocumentManager pdm = PsiDocumentManager.getInstance(project);
        Document doc = FileDocumentManager.getInstance().getDocument(file);
        if (doc != null) {
            pdm.doPostponedOperationsAndUnblockDocument(doc);
            pdm.commitDocument(doc);
        } else {
            pdm.commitAllDocuments();
        }

        PsiFile psiFile = PsiManager.getInstance(project).findFile(file);
        if (psiFile != null) {
            CodeStyleManager.getInstance(project).reformat(psiFile);
            if (doc != null) {
                FileDocumentManager.getInstance().saveDocument(doc);
            }
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

    // 中文注释：通用整数夹取函数，保证值落入 [min, max] 范围
    private int clamp(int val, int min, int max) {
        return Math.max(min, Math.min(max, val));
    }
}
