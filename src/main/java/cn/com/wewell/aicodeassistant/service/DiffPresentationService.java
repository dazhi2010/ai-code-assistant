package cn.com.wewell.aicodeassistant.service;

import cn.com.wewell.aicodeassistant.action.diff.ApplyChangeAction;
import cn.com.wewell.aicodeassistant.model.AiResponseAction;
import com.intellij.diff.DiffContentFactory;
import com.intellij.diff.DiffManager;
import com.intellij.diff.contents.DiffContent;
import com.intellij.diff.requests.SimpleDiffRequest;
import com.intellij.diff.util.DiffUserDataKeys;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.vfs.VirtualFile;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

@Service(Service.Level.PROJECT)
public final class DiffPresentationService {

    private final Project project;

    public DiffPresentationService(Project project) {
        this.project = project;
    }

    public static DiffPresentationService getInstance(Project project) {
        return project.getService(DiffPresentationService.class);
    }

    public void showDiffForFile(List<AiResponseAction> actionsForFile) {
        if (actionsForFile == null || actionsForFile.isEmpty()) return;
        String filePath = actionsForFile.get(0).filePath();

        // 1. 读取原始文件内容
        Computable<DiffData> readComputable = () -> {
            VirtualFile file = ChangeApplierService.getInstance(project).findVirtualFile(filePath);
            String originalContent = "";
            if (file != null && file.exists()) {
                Document doc = FileDocumentManager.getInstance().getDocument(file);
                if (doc != null) originalContent = doc.getText();
            }
            return new DiffData(file, originalContent);
        };
        DiffData diffData = ApplicationManager.getApplication().runReadAction(readComputable);
        VirtualFile file = diffData.file;

        // 2. 创建左侧内容
        DiffContent content1 = DiffContentFactory.getInstance().create(project, diffData.originalContent, file != null ? file.getFileType() : null);

        // 3. 通过字符串操作创建右侧预览内容
        String previewContent = createPreviewContentByStringManipulation(diffData.originalContent, actionsForFile);
        DiffContent content2 = DiffContentFactory.getInstance().create(project, previewContent, file != null ? file.getFileType() : null);

        // 4. 创建 Diff 请求
        String title = "聚合变更预览: " + filePath;
        SimpleDiffRequest request = new SimpleDiffRequest(title, content1, content2, "当前内容", "AI 生成的最终效果");

        // 5. 添加“应用”按钮
        List<AnAction> actions = new ArrayList<>();
        actions.add(new ApplyChangeAction(actionsForFile));
        request.putUserData(DiffUserDataKeys.CONTEXT_ACTIONS, actions);

        // 6. 直接调用 showDiff，让平台处理显示逻辑
        DiffManager.getInstance().showDiff(project, request);
    }

    private String createPreviewContentByStringManipulation(String originalContent, List<AiResponseAction> actions) {
        AiResponseAction overwriteAction = actions.stream().filter(a -> "OVERWRITE".equalsIgnoreCase(a.action())).findFirst().orElse(null);
        if (overwriteAction != null) return overwriteAction.content();

        AiResponseAction createAction = actions.stream().filter(a -> "CREATE".equalsIgnoreCase(a.action())).findFirst().orElse(null);
        if (createAction != null) return createAction.content();

        List<String> lines = new ArrayList<>(Arrays.asList(originalContent.split("\\R", -1)));

        actions.sort(Comparator.comparingInt((AiResponseAction a) -> ChangeApplierService.getInstance(project).getActionStartLine(a)).reversed());

        for (AiResponseAction action : actions) {
            try {
                switch (action.action().toUpperCase()) {
                    case "UPDATE": {
                        int startLine = action.startLine() - 1;
                        int endLine = action.endLine() - 1;
                        if (startLine < 0 || endLine >= lines.size() || startLine > endLine) continue;
                        for (int i = endLine; i >= startLine; i--) lines.remove(i);
                        List<String> newLines = Arrays.asList(action.content().split("\\R"));
                        lines.addAll(startLine, newLines);
                        break;
                    }
                    case "INSERT": {
                        int line = action.line() - 1;
                        if (line < 0 || line > lines.size()) continue;
                        List<String> newLines = Arrays.asList(action.content().split("\\R"));
                        lines.addAll(line, newLines);
                        break;
                    }
                    case "DELETE": {
                        if (action.startLine() != null && action.endLine() != null) {
                            int startLine = action.startLine() - 1;
                            int endLine = action.endLine() - 1;
                            if (startLine < 0 || endLine >= lines.size() || startLine > endLine) continue;
                            for (int i = endLine; i >= startLine; i--) lines.remove(i);
                        } else {
                            lines.clear();
                        }
                        break;
                    }
                }
            } catch (Exception e) {
                // 忽略模拟中的错误
            }
        }
        return String.join("\n", lines);
    }

    private record DiffData(VirtualFile file, String originalContent) {}
}