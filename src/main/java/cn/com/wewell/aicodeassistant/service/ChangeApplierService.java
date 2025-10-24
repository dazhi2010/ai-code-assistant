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
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
                List<AiResponseAction> fileActions = entry.getValue();

                // 优先处理 OVERWRITE，因为它会覆盖所有其他变更
                Optional<AiResponseAction> overwriteAction = fileActions.stream()
                        .filter(a -> "OVERWRITE".equalsIgnoreCase(a.action()))
                        .findFirst();

                if (overwriteAction.isPresent()) {
                    applyOverwrite(overwriteAction.get());
                    continue;
                }

                // 对于同一个文件的多个操作，按顺序执行
                // 注意：如果操作之间存在重叠，AI响应的顺序至关重要。
                // 理论上，更安全的做法是每次修改后都重新加载内容，但会牺牲性能。
                // 当前假设AI提供的操作是按逻辑顺序排列的。
                for (AiResponseAction action : fileActions) {
                    applyActionByContent(action);
                }
            }
            notifySuccess("已成功应用 " + actions.size() + " 个变更。");
        }, "Apply AI Assistant Changes", null);
    }

    private void applyActionByContent(AiResponseAction action) {
        try {
            switch (action.action().toUpperCase()) {
                case "CREATE" -> applyCreate(action);
                case "OVERWRITE" -> applyOverwrite(action); // 已经预先处理，但保留以防万一
                case "UPDATE" -> applyUpdateByContent(action);
                case "INSERT_AFTER" -> applyInsertAfterByContent(action);
                case "DELETE" -> applyDeleteByContent(action);
                default -> notifyWarning("未知的操作类型: " + action.action());
            }
        } catch (IOException e) {
            notifyError("应用变更失败 '" + action.action() + "' on " + action.filePath() + ": " + e.getMessage());
        }
    }

    private void applyCreate(AiResponseAction action) throws IOException {
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
    }

    private void applyOverwrite(AiResponseAction action) {
        WriteCommandAction.runWriteCommandAction(project, () -> {
            Document document = getDocument(action.filePath());
            if (document == null) {
                // 如果文件不存在，则尝试创建它
                try {
                    applyCreate(action);
                } catch (IOException e) {
                    notifyError("文件不存在，创建失败: " + action.filePath());
                }
                return;
            }
            document.replaceString(0, document.getTextLength(), action.content());
            FileDocumentManager.getInstance().saveDocument(document);
            formatFile(FileDocumentManager.getInstance().getFile(document));
        });
    }

    private void applyUpdateByContent(AiResponseAction action) {
        WriteCommandAction.runWriteCommandAction(project, () -> {
            Document document = getDocumentForModification(action.filePath());
            if (document == null) return;

            findUniqueBlock(document.getText(), action.oldCodeBlock(), action.filePath()).ifPresent(range -> {
                document.replaceString(range.startOffset(), range.endOffset(), Objects.requireNonNullElse(action.newCodeBlock(), ""));
                FileDocumentManager.getInstance().saveDocument(document);
            });
        });
    }

    private void applyInsertAfterByContent(AiResponseAction action) {
        WriteCommandAction.runWriteCommandAction(project, () -> {
            Document document = getDocumentForModification(action.filePath());
            if (document == null) return;

            findUniqueBlock(document.getText(), action.oldCodeBlock(), action.filePath()).ifPresent(range -> {
                document.insertString(range.endOffset(), Objects.requireNonNullElse(action.newCodeBlock(), ""));
                FileDocumentManager.getInstance().saveDocument(document);
            });
        });
    }

    private void applyDeleteByContent(AiResponseAction action) throws IOException {
        // 如果 oldCodeBlock 为空，则认为是删除整个文件
        if (action.oldCodeBlock() == null || action.oldCodeBlock().isEmpty()) {
            WriteCommandAction.runWriteCommandAction(project, () -> {
                VirtualFile file = findVirtualFile(action.filePath());
                if (file != null && file.exists()) {
                    try {
                        file.delete(this);
                        notifySuccess("文件已删除: " + action.filePath());
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                } else {
                    notifyWarning("尝试删除但文件未找到: " + action.filePath());
                }
            });
            return;
        }

        // 否则，删除文件中的代码块
        WriteCommandAction.runWriteCommandAction(project, () -> {
            Document document = getDocumentForModification(action.filePath());
            if (document == null) return;

            findUniqueBlock(document.getText(), action.oldCodeBlock(), action.filePath()).ifPresent(range -> {
                document.deleteString(range.startOffset(), range.endOffset());
                FileDocumentManager.getInstance().saveDocument(document);
            });
        });
    }

    private Optional<BlockRange> findUniqueBlock(String content, String blockToFind, String filePath) {
        if (blockToFind == null || blockToFind.isEmpty()) {
            notifyWarning("操作被跳过：代码块为空 on " + filePath);
            return Optional.empty();
        }

        int startIndex = content.indexOf(blockToFind);
        if (startIndex == -1) {
            notifyError("应用变更失败：在 " + filePath + " 中找不到指定的代码块。");
            return Optional.empty();
        }

        int lastIndex = content.lastIndexOf(blockToFind);
        if (startIndex != lastIndex) {
            notifyError("应用变更失败：在 " + filePath + " 中找到多个相同的代码块，存在歧义。");
            return Optional.empty();
        }

        return Optional.of(new BlockRange(startIndex, startIndex + blockToFind.length()));
    }

    private Document getDocumentForModification(String relativePath) {
        Document document = getDocument(relativePath);
        if (document == null) {
            notifyWarning("文件未找到，无法应用变更: " + relativePath);
            return null;
        }
        // 确保 Document 和 PSI 状态同步
        PsiDocumentManager pdm = PsiDocumentManager.getInstance(project);
        pdm.doPostponedOperationsAndUnblockDocument(document);
        pdm.commitDocument(document);
        return document;
    }

    public VirtualFile findVirtualFile(String relativePath) {
        String projectBasePath = project.getBasePath();
        if (projectBasePath == null) return null;

        String fullPath = new File(projectBasePath, relativePath.replace('/', File.separatorChar)).getAbsolutePath();
        return LocalFileSystem.getInstance().refreshAndFindFileByPath(fullPath);
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
            WriteCommandAction.runWriteCommandAction(project, () -> {
                CodeStyleManager.getInstance(project).reformat(psiFile);
                Document doc = FileDocumentManager.getInstance().getDocument(file);
                if (doc != null) {
                    FileDocumentManager.getInstance().saveDocument(doc);
                }
            });
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

    private record BlockRange(int startOffset, int endOffset) {}
}
