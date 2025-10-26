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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
                String newText = Objects.requireNonNullElse(action.newCodeBlock(), "");
                newText = newText.replace("\r\n", "\n");
                document.replaceString(range.startOffset(), range.endOffset(), newText);
                FileDocumentManager.getInstance().saveDocument(document);
            });
        });
    }

    private void applyInsertAfterByContent(AiResponseAction action) {
        WriteCommandAction.runWriteCommandAction(project, () -> {
            Document document = getDocumentForModification(action.filePath());
            if (document == null) return;

            findUniqueBlock(document.getText(), action.oldCodeBlock(), action.filePath()).ifPresent(range -> {
                String newText = Objects.requireNonNullElse(action.newCodeBlock(), "");
                newText = newText.replace("\r\n", "\n");
                document.insertString(range.endOffset(), newText);
                FileDocumentManager.getInstance().saveDocument(document);
            });
        });
    }

    private void applyDeleteByContent(AiResponseAction action) throws IOException {
        // oldCodeBlock 为空 => 删除整个文件
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

        // 否则，删除文件中的代码块；找不到时做“整文件等价”兜底
        WriteCommandAction.runWriteCommandAction(project, () -> {
            Document document = getDocumentForModification(action.filePath());
            if (document == null) return;

            String fileText = document.getText();
            Optional<BlockRange> range = findUniqueBlock(fileText, action.oldCodeBlock(), action.filePath());
            if (range.isPresent()) {
                document.deleteString(range.get().startOffset(), range.get().endOffset());
                FileDocumentManager.getInstance().saveDocument(document);
                return;
            }

            // 兜底：若 oldCodeBlock 与整文件在“宽松规范化后”完全一致，按“删除整个文件”处理
            if (contentEquivalentForDeletion(fileText, action.oldCodeBlock())) {
                VirtualFile file = findVirtualFile(action.filePath());
                if (file != null && file.exists()) {
                    try {
                        file.delete(this);
                        notifySuccess("文件已删除: " + action.filePath() + "（宽松等价匹配）");
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                } else {
                    notifyWarning("尝试删除但文件未找到: " + action.filePath());
                }
            }
        });
    }

    private boolean contentEquivalentForDeletion(String fileText, String blockText) {
        return normalizeForCompare(fileText).equals(normalizeForCompare(blockText));
    }

    // 规范化：统一换行；去掉每行行首空白与 Javadoc 边界星号；去掉所有空格/Tab
    private String normalizeForCompare(String s) {
        if (s == null) return "";
        s = s.replace("\r\n", "\n").replace("\r", "\n");
        StringBuilder sb = new StringBuilder(s.length());
        int i = 0, len = s.length();
        while (i < len) {
            int j = s.indexOf('\n', i);
            if (j == -1) j = len;
            String line = s.substring(i, j);
            // 去掉行首空白与若存在的 Javadoc 星号
            line = line.replaceFirst("^\\s*\\*?\\s*", "");
            // 去掉所有空格/Tab
            line = line.replaceAll("[ \\t]+", "");
            sb.append(line);
            if (j < len) sb.append('\n');
            i = j + 1;
        }
        return sb.toString();
    }

    private Optional<BlockRange> findUniqueBlock(String content, String blockToFind, String filePath) {
        if (blockToFind == null || blockToFind.isEmpty()) {
            notifyWarning("操作被跳过：代码块为空 on " + filePath);
            return Optional.empty();
        }

        // 1) 先尝试完全匹配
        int first = content.indexOf(blockToFind);
        if (first >= 0) {
            int last = content.lastIndexOf(blockToFind);
            if (first != last) {
                notifyError("应用变更失败：在 " + filePath + " 中找到多个相同的代码块，存在歧义。");
                return Optional.empty();
            }
            return Optional.of(new BlockRange(first, first + blockToFind.length()));
        }

        // 2) 宽松匹配（忽略空白/换行差异、Javadoc行首星号、/** vs /*、*/ vs **/）
        Pattern loose = buildLooseBlockPattern(blockToFind);
        Matcher m = loose.matcher(content);
        if (!m.find()) {
            notifyError("应用变更失败：在 " + filePath + " 中找不到指定的代码块（已使用宽松匹配）。");
            return Optional.empty();
        }
        int start = m.start();
        int end = m.end();

        // 确保唯一
        if (m.find()) {
            notifyError("应用变更失败：在 " + filePath + " 中找到多个疑似匹配的代码块（宽松匹配），存在歧义。");
            return Optional.empty();
        }
        return Optional.of(new BlockRange(start, end));
    }

    private Pattern buildLooseBlockPattern(String block) {
        StringBuilder rx = new StringBuilder(block.length() * 2);
        boolean lineStart = true;

        for (int i = 0; i < block.length();) {
            char c = block.charAt(i);

            // 忽略 CR
            if (c == '\r') { i++; continue; }

            // 换行：兼容 CRLF/LF，并允许行首有/无 Javadoc 星号与空白
            if (c == '\n') {
                rx.append("(?:\\r?\\n)"); // 换行
                // 行首：可选缩进 + 可选一个或多个星号 + 可选空白
                rx.append("[ \\t]*(?:\\*+\\s*)?");
                lineStart = true;
                i++;
                continue;
            }

            // 处理块起始行（第一行也视作行首）
            if (lineStart) {
                // 容忍开头缩进 + 0..N 星号差异
                rx.append("[ \\t]*(?:\\*+\\s*)?");
                // 消耗 block 中行首的空白和星号，避免重复匹配
                while (i < block.length()) {
                    char d = block.charAt(i);
                    if (d == ' ' || d == '\t' || d == '*') { i++; } else break;
                }
                lineStart = false;
                continue;
            }

            // 统一 "/**" 与 "/*"
            if (c == '/' && i + 1 < block.length() && block.charAt(i + 1) == '*') {
                // 允许 1 或 2 个星
                rx.append("/\\*{1,2}");
                i += 2; // 跳过 "/*"
                // 若 old 块是 "/**" 则再跳过一个星
                if (i < block.length() && block.charAt(i) == '*') i++;
                continue;
            }

            // 统一 "*/" 与 "**/"
            if (c == '*' && i + 1 < block.length() && block.charAt(i + 1) == '/') {
                rx.append("\\*+/"); // 至少一个星再跟斜杠
                i += 2;
                continue;
            }

            // 折叠空白
            if (c == ' ' || c == '\t') {
                rx.append("[ \\t]+");
                while (i < block.length() && (block.charAt(i) == ' ' || block.charAt(i) == '\t')) i++;
                continue;
            }

            // 其他字符按字面匹配
            rx.append(Pattern.quote(String.valueOf(c)));
            i++;
        }

        return Pattern.compile(rx.toString(), Pattern.MULTILINE);
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
