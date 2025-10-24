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
import java.util.List;
import java.util.Objects;
import java.util.Optional;

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

        // 2. 创建左侧内容 (原始文件)
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

        // 6. 显示 Diff 窗口
        DiffManager.getInstance().showDiff(project, request);
    }

    private String createPreviewContentByStringManipulation(String originalContent, List<AiResponseAction> actions) {
        // 优先处理 OVERWRITE 和 CREATE，它们决定了全部内容
        AiResponseAction overwriteAction = actions.stream().filter(a -> "OVERWRITE".equalsIgnoreCase(a.action())).findFirst().orElse(null);
        if (overwriteAction != null) return overwriteAction.content();

        AiResponseAction createAction = actions.stream().filter(a -> "CREATE".equalsIgnoreCase(a.action())).findFirst().orElse(null);
        if (createAction != null) return createAction.content();

        StringBuilder previewBuilder = new StringBuilder(originalContent);

        // 按顺序应用变更来生成预览
        for (AiResponseAction action : actions) {
            String oldBlock = action.oldCodeBlock();
            String newBlock = action.newCodeBlock();

            // 对于需要锚点代码块的操作，如果锚点为空则跳过
            if (oldBlock == null || oldBlock.isEmpty()) {
                continue;
            }

            // 在当前预览内容中查找唯一的代码块
            findUniqueBlockOffsets(previewBuilder.toString(), oldBlock).ifPresent(range -> {
                switch (action.action().toUpperCase()) {
                    case "UPDATE":
                        previewBuilder.replace(range.startOffset(), range.endOffset(), Objects.requireNonNullElse(newBlock, ""));
                        break;
                    case "INSERT_AFTER":
                        previewBuilder.insert(range.endOffset(), Objects.requireNonNullElse(newBlock, ""));
                        break;
                    case "DELETE":
                        previewBuilder.delete(range.startOffset(), range.endOffset());
                        break;
                    default:
                        // 其他操作类型在预览中忽略
                        break;
                }
            });
        }

        return previewBuilder.toString();
    }

    /**
     * 在给定内容中查找唯一的代码块，并返回其起始和结束偏移量。
     * 如果找不到或找到多个，则返回 Optional.empty()。
     */
    private Optional<BlockRange> findUniqueBlockOffsets(String content, String blockToFind) {
        int startIndex = content.indexOf(blockToFind);
        if (startIndex == -1) {
            return Optional.empty(); // 找不到
        }

        int lastIndex = content.lastIndexOf(blockToFind);
        if (startIndex != lastIndex) {
            return Optional.empty(); // 存在歧义
        }

        return Optional.of(new BlockRange(startIndex, startIndex + blockToFind.length()));
    }

    private record DiffData(VirtualFile file, String originalContent) {}
    private record BlockRange(int startOffset, int endOffset) {}
}
