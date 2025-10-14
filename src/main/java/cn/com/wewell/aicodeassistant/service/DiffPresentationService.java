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

        // 3. 通过纯字符串操作创建右侧预览内容
        String previewContent = createPreviewContentByStringManipulation(diffData.originalContent, actionsForFile);
        DiffContent content2 = DiffContentFactory.getInstance().create(project, previewContent, file != null ? file.getFileType() : null);

        // 4. 创建 Diff 请求
        String title = "聚合变更预览: " + filePath;
        SimpleDiffRequest request = new SimpleDiffRequest(title, content1, content2, "当前内容", "AI 生成的最终效果");

        // 5. 添加“应用”按钮
        List<AnAction> actions = new ArrayList<>();
        actions.add(new ApplyChangeAction(actionsForFile));
        request.putUserData(DiffUserDataKeys.CONTEXT_ACTIONS, actions);

        // 6. 直接调用 showDiff
        DiffManager.getInstance().showDiff(project, request);
    }

    /**
     * 最终解决方案：完全通过字符串操作来生成预览内容，避免任何Document对象的副作用。
     */
    private String createPreviewContentByStringManipulation(String originalContent, List<AiResponseAction> actions) {
        // 优先处理 OVERWRITE 和 CREATE
        AiResponseAction overwriteAction = actions.stream().filter(a -> "OVERWRITE".equalsIgnoreCase(a.action())).findFirst().orElse(null);
        if (overwriteAction != null) return overwriteAction.content();

        AiResponseAction createAction = actions.stream().filter(a -> "CREATE".equalsIgnoreCase(a.action())).findFirst().orElse(null);
        if (createAction != null) return createAction.content();

        // 将原始文本按行分割成一个可修改的列表
        List<String> lines = new ArrayList<>(Arrays.asList(originalContent.split("\\R", -1)));

        // 按行号倒序排序操作，这样在修改时不会影响前面操作的行索引
        actions.sort(Comparator.comparingInt((AiResponseAction a) -> ChangeApplierService.getInstance(project).getActionStartLine(a)).reversed());

        for (AiResponseAction action : actions) {
            try {
                switch (action.action().toUpperCase()) {
                    case "UPDATE": {
                        int startLine = action.startLine() - 1;
                        int endLine = action.endLine() - 1;
                        if (startLine < 0 || endLine >= lines.size() || startLine > endLine) continue;

                        // 先从后往前删除
                        for (int i = endLine; i >= startLine; i--) {
                            lines.remove(i);
                        }
                        // 再在起始位置插入新行
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
                            for (int i = endLine; i >= startLine; i--) {
                                lines.remove(i);
                            }
                        } else {
                            lines.clear();
                        }
                        break;
                    }
                }
            } catch (Exception e) {
                // 忽略模拟中的任何错误
            }
        }

        // 将行列表重新组合成一个用 \n 分隔的字符串
        return String.join("\n", lines);
    }

    private record DiffData(VirtualFile file, String originalContent) {}
}