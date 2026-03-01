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

/**
 * 负责将 AI 建议的变更应用到项目文件的核心服务。
 * 完美还原了 CodeBlockMatcherTest 的逻辑：基于 Token 的灵活匹配、前后空白保留、智能缩进平移与动态相似度兜底。
 *
 * @author yuqf
 */
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
        if (actions == null || actions.isEmpty()) return;

        Map<String, List<AiResponseAction>> groupedActions = actions.stream()
                .collect(Collectors.groupingBy(AiResponseAction::filePath));

        WriteCommandAction.runWriteCommandAction(project, () -> {
            CommandProcessor.getInstance().executeCommand(project, () -> {
                for (Map.Entry<String, List<AiResponseAction>> entry : groupedActions.entrySet()) {
                    String filePath = entry.getKey();
                    List<AiResponseAction> fileActions = entry.getValue();

                    // 优先处理 OVERWRITE
                    Optional<AiResponseAction> overwriteAction = fileActions.stream()
                            .filter(a -> "OVERWRITE".equalsIgnoreCase(a.action()))
                            .findFirst();

                    if (overwriteAction.isPresent()) {
                        applyOverwriteDirectly(overwriteAction.get());
                        continue;
                    }

                    // 批量应用变更
                    for (AiResponseAction action : fileActions) {
                        try {
                            applyActionWithoutFormatting(action);
                        } catch (Exception e) {
                            notifyError("应用变更失败 [" + action.action() + "] -> " + action.filePath() + ": " + e.getMessage());
                        }
                    }

                    // 格式化处理（由于缩进已智能调整，有时可以不用强行全局格式化，但为了兜底仍保留）
                    VirtualFile vf = findVirtualFile(filePath);
                    if (vf != null) {
                        formatFileDirectly(vf);
                    }
                }
                notifySuccess("已成功应用 " + actions.size() + " 个变更。");
            }, "Apply AI Assistant Changes", null);
        });
    }

    private void applyActionWithoutFormatting(AiResponseAction action) throws IOException {
        String actionType = action.action().toUpperCase();

        if ("CREATE".equals(actionType)) {
            applyCreateWithoutFormatting(action);
            return;
        }

        if ("DELETE".equals(actionType) && (action.oldCodeBlock() == null || action.oldCodeBlock().isEmpty())) {
            VirtualFile file = findVirtualFile(action.filePath());
            if (file != null && file.exists()) file.delete(this);
            return;
        }

        Document document = getDocumentForModification(action.filePath());
        if (document == null) return;

        String oldContent = document.getText();
        String newContent = applyChangeToContent(oldContent, action);

        if (!oldContent.equals(newContent)) {
            document.setText(newContent);
        }
    }

    private void applyCreateWithoutFormatting(AiResponseAction action) throws IOException {
        String projectBasePath = project.getBasePath();
        if (projectBasePath == null) return;

        String correctedRelativePath = action.filePath().replace('/', File.separatorChar);
        File targetFile = new File(projectBasePath, correctedRelativePath);
        File parentDir = targetFile.getParentFile();
        if (parentDir == null) return;

        VirtualFile parentVirtualDir = VfsUtil.createDirectories(parentDir.getAbsolutePath());
        if (parentVirtualDir == null) throw new IOException("无法创建目录: " + parentDir.getAbsolutePath());

        VirtualFile newFile = parentVirtualDir.findChild(targetFile.getName());
        if (newFile == null) {
            newFile = parentVirtualDir.createChildData(this, targetFile.getName());
        }

        String content = Objects.requireNonNullElse(action.content(), action.newCodeBlock());
        if (content != null) {
            newFile.setBinaryContent(content.getBytes());
        }
    }

    private void applyOverwriteDirectly(AiResponseAction action) {
        Document document = getDocument(action.filePath());
        if (document == null) {
            try {
                applyCreateWithoutFormatting(action);
                VirtualFile vf = findVirtualFile(action.filePath());
                if (vf != null) formatFileDirectly(vf);
            } catch (IOException e) {
                notifyError("文件不存在且创建失败: " + action.filePath());
            }
            return;
        }
        document.setText(Objects.requireNonNullElse(action.content(), ""));
        FileDocumentManager.getInstance().saveDocument(document);
        VirtualFile vf = FileDocumentManager.getInstance().getFile(document);
        if (vf != null) formatFileDirectly(vf);
    }

    /**
     * 将单个变更应用于文本内容，核心使用 CodeBlockMatcherTest 的逻辑。
     * 公开此方法，确保 Diff 预览与实际应用的匹配逻辑完全一致。
     */
    public String applyChangeToContent(String targetCode, AiResponseAction action) {
        String actionType = action.action().toUpperCase();
        String oldCodeBlock = action.oldCodeBlock();
        String newCodeBlock = action.newCodeBlock() != null ? action.newCodeBlock() : "";

        if (oldCodeBlock == null || oldCodeBlock.trim().isEmpty()) {
            return targetCode;
        }

        // 统一换行符
        targetCode = targetCode.replace("\r\n", "\n").replace("\r", "\n");
        oldCodeBlock = oldCodeBlock.replace("\r\n", "\n").replace("\r", "\n");
        newCodeBlock = newCodeBlock.replace("\r\n", "\n").replace("\r", "\n");

        // 步骤1: 构建灵活正则表达式
        String regexPattern = buildFlexibleRegex(oldCodeBlock);
        Pattern pattern = Pattern.compile(regexPattern, Pattern.DOTALL);
        Matcher matcher = pattern.matcher(targetCode);

        int startPos = -1;
        int endPos = -1;
        String matchedContent = null;

        if (matcher.find()) {
            startPos = matcher.start();
            endPos = matcher.end();
            matchedContent = matcher.group();
            
            // 确保没有歧义
            if (matcher.find()) {
                startPos = -1; // 有多个匹配，回退到备用策略
            }
        }

        if (startPos >= 0) {
            return applyToExactPosition(targetCode, startPos, endPos, matchedContent, newCodeBlock, actionType);
        } else {
            // 步骤2: 备用策略，基于相似度
            return tryAlternativeMatch(targetCode, oldCodeBlock, newCodeBlock, actionType);
        }
    }

    private String buildFlexibleRegex(String oldCodeBlock) {
        String normalized = oldCodeBlock.replaceAll("\\s+/>", "/>");
        String[] tokens = normalized.trim().split("\\s+");
        StringBuilder regex = new StringBuilder();

        for (int i = 0; i < tokens.length; i++) {
            if (tokens[i].isEmpty()) continue;
            if (i > 0) regex.append("\\s+");

            String token = tokens[i];
            if (token.endsWith("/>")) {
                String prefix = token.substring(0, token.length() - 2);
                regex.append(escapeTokenForRegex(prefix)).append("\\s*/").append(">");
            } else {
                regex.append(escapeTokenForRegex(token));
            }
        }
        return regex.toString();
    }

    private String escapeTokenForRegex(String token) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if (c == '"' || c == '\'') {
                sb.append("[\"']");
            } else if ("<>()[]{}\\^$|?*+.".indexOf(c) != -1) {
                sb.append("\\").append(c);
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private String applyToExactPosition(String targetCode, int startPos, int endPos, String matchedContent, String newCodeBlock, String actionType) {
        if ("DELETE".equals(actionType)) {
            return targetCode.substring(0, startPos) + targetCode.substring(endPos);
        }

        String indent = detectIndent(matchedContent);
        String adjustedNewCode = adjustIndent(newCodeBlock, indent);

        if ("INSERT_AFTER".equals(actionType)) {
            if (!adjustedNewCode.startsWith("\n")) adjustedNewCode = "\n" + adjustedNewCode;
            return targetCode.substring(0, endPos) + adjustedNewCode + targetCode.substring(endPos);
        }

        // UPDATE 处理前后空白保留
        String leadingWhitespace = "";
        String trailingWhitespace = "";

        for (int i = 0; i < matchedContent.length(); i++) {
            char c = matchedContent.charAt(i);
            if (c == '\n' || c == '\r') {
                leadingWhitespace += c;
            } else if (Character.isWhitespace(c) && leadingWhitespace.contains("\n")) {
                break;
            } else if (Character.isWhitespace(c)) {
                leadingWhitespace += c;
            } else {
                break;
            }
        }

        for (int i = matchedContent.length() - 1; i >= 0; i--) {
            char c = matchedContent.charAt(i);
            if (c == '\n' || c == '\r' || Character.isWhitespace(c)) {
                trailingWhitespace = c + trailingWhitespace;
            } else {
                break;
            }
        }

        String finalNewCode = leadingWhitespace + adjustedNewCode + trailingWhitespace;
        return targetCode.substring(0, startPos) + finalNewCode + targetCode.substring(endPos);
    }

    private String tryAlternativeMatch(String targetCode, String oldCodeBlock, String newCodeBlock, String actionType) {
        String normalizedOld = normalizeForMatching(oldCodeBlock);

        String[] targetLines = targetCode.split("\n", -1);
        String[] oldLines = oldCodeBlock.split("\n");

        int oldNonEmptyLines = 0;
        for (String line : oldLines) {
            if (!line.trim().isEmpty()) oldNonEmptyLines++;
        }

        int startLine = -1;
        int endLine = -1;
        double bestSimilarity = 0.0;

        for (int i = 0; i < targetLines.length; i++) {
            for (int j = i + Math.max(0, oldNonEmptyLines - 3); j < Math.min(targetLines.length, i + oldNonEmptyLines + 5); j++) {
                StringBuilder window = new StringBuilder();
                for (int k = i; k <= j; k++) {
                    window.append(targetLines[k]).append("\n");
                }

                String normalizedWindow = normalizeForMatching(window.toString());
                
                int maxLen = Math.max(normalizedOld.length(), normalizedWindow.length());
                if (maxLen == 0) continue;
                int lenDiff = Math.abs(normalizedOld.length() - normalizedWindow.length());
                if (1.0 - (double) lenDiff / maxLen <= bestSimilarity) {
                    continue;
                }

                double similarity = calculateSimilarity(normalizedOld, normalizedWindow);

                if (similarity > bestSimilarity) {
                    bestSimilarity = similarity;
                    startLine = i;
                    endLine = j;
                }
            }
        }

        if (bestSimilarity < 0.8 || startLine < 0) {
            throw new RuntimeException("正则与相似度匹配均失败。最高相似度仅为: " + String.format("%.2f%%", bestSimilarity * 100));
        }

        if ("DELETE".equals(actionType)) {
            StringBuilder result = new StringBuilder();
            for (int i = 0; i < startLine; i++) result.append(targetLines[i]).append("\n");
            for (int i = endLine + 1; i < targetLines.length; i++) {
                result.append(targetLines[i]);
                if (i < targetLines.length - 1) result.append("\n");
            }
            return result.toString();
        }

        String indent = detectIndent(targetLines[startLine]);
        String adjustedNewCode = adjustIndent(newCodeBlock, indent);

        if ("INSERT_AFTER".equals(actionType)) {
            StringBuilder result = new StringBuilder();
            for (int i = 0; i <= endLine; i++) result.append(targetLines[i]).append("\n");
            result.append(adjustedNewCode).append("\n");
            for (int i = endLine + 1; i < targetLines.length; i++) {
                result.append(targetLines[i]);
                if (i < targetLines.length - 1) result.append("\n");
            }
            return result.toString();
        }

        // UPDATE
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < startLine; i++) result.append(targetLines[i]).append("\n");
        result.append(adjustedNewCode);
        if (endLine + 1 < targetLines.length) result.append("\n");
        for (int i = endLine + 1; i < targetLines.length; i++) {
            result.append(targetLines[i]);
            if (i < targetLines.length - 1) result.append("\n");
        }

        return result.toString();
    }

    private String normalizeForMatching(String code) {
        return code.replaceAll("\\s+", " ")
                   .replace('"', '\'')
                   .replace("/>", " />")
                   .replaceAll("\\s+/>", " />")
                   .trim();
    }

    private double calculateSimilarity(String s1, String s2) {
        int maxLen = Math.max(s1.length(), s2.length());
        if (maxLen == 0) return 1.0;
        int distance = levenshteinDistance(s1, s2);
        return 1.0 - (double) distance / maxLen;
    }

    private int levenshteinDistance(String s1, String s2) {
        int[][] dp = new int[s1.length() + 1][s2.length() + 1];
        for (int i = 0; i <= s1.length(); i++) dp[i][0] = i;
        for (int j = 0; j <= s2.length(); j++) dp[0][j] = j;

        for (int i = 1; i <= s1.length(); i++) {
            for (int j = 1; j <= s2.length(); j++) {
                int cost = s1.charAt(i - 1) == s2.charAt(j - 1) ? 0 : 1;
                dp[i][j] = Math.min(
                        Math.min(dp[i - 1][j] + 1, dp[i][j - 1] + 1),
                        dp[i - 1][j - 1] + cost
                );
            }
        }
        return dp[s1.length()][s2.length()];
    }

    private String detectIndent(String code) {
        String[] lines = code.split("\n");
        for (String line : lines) {
            if (!line.trim().isEmpty()) {
                int count = 0;
                for (char c : line.toCharArray()) {
                    if (c == ' ') count++;
                    else break;
                }
                return " ".repeat(count);
            }
        }
        return "";
    }

    private String adjustIndent(String code, String targetIndent) {
        String[] lines = code.split("\n", -1);
        int minIndent = Integer.MAX_VALUE;
        for (String line : lines) {
            if (!line.trim().isEmpty()) {
                int spaces = 0;
                for (char c : line.toCharArray()) {
                    if (c == ' ') spaces++;
                    else if (c == '\t') spaces += 4;
                    else break;
                }
                minIndent = Math.min(minIndent, spaces);
            }
        }
        if (minIndent == Integer.MAX_VALUE) minIndent = 0;

        StringBuilder result = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) result.append("\n");
            String line = lines[i];
            if (line.trim().isEmpty()) {
                result.append("");
            } else {
                int currentIndent = 0;
                for (char c : line.toCharArray()) {
                    if (c == ' ') currentIndent++;
                    else if (c == '\t') currentIndent += 4;
                    else break;
                }
                int relativeIndent = Math.max(0, currentIndent - minIndent);
                result.append(targetIndent).append(" ".repeat(relativeIndent)).append(line.trim());
            }
        }
        return result.toString();
    }

    private Document getDocumentForModification(String relativePath) {
        Document document = getDocument(relativePath);
        if (document == null) return null;
        PsiDocumentManager pdm = PsiDocumentManager.getInstance(project);
        pdm.doPostponedOperationsAndUnblockDocument(document);
        pdm.commitDocument(document);
        return document;
    }

    public VirtualFile findVirtualFile(String relativePath) {
        String projectBase = project.getBasePath();
        if (projectBase == null) return null;
        String fullPath = new File(projectBase, relativePath.replace('/', File.separatorChar)).getAbsolutePath();
        return LocalFileSystem.getInstance().refreshAndFindFileByPath(fullPath);
    }

    private Document getDocument(String relativePath) {
        VirtualFile file = findVirtualFile(relativePath);
        if (file == null) return null;
        return FileDocumentManager.getInstance().getDocument(file);
    }

    private void formatFileDirectly(VirtualFile file) {
        PsiFile psiFile = PsiManager.getInstance(project).findFile(file);
        if (psiFile != null) {
            CodeStyleManager.getInstance(project).reformat(psiFile);
            Document doc = FileDocumentManager.getInstance().getDocument(file);
            if (doc != null) FileDocumentManager.getInstance().saveDocument(doc);
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