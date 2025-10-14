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
     * 修复点：
     * 1) 之前按行拆分使用 split("\\R", -1) 会在文件末尾有换行时引入“虚拟空行”，导致 UPDATE 的 endLine 越界而被忽略；
     * 2) 对传入的行号进行“夹取”(clamp) 与空文件保护，允许 AI 给出的行号略微超出边界（如指向文件最后一行之后），
     *    从而在如 DEPLOYMENT_REMOTE_NOTES.md 这类文件上也能得到正确的右侧预览内容。
     */
    private String createPreviewContentByStringManipulation(String originalContent, List<AiResponseAction> actions) {
        // 优先处理 OVERWRITE 和 CREATE
        AiResponseAction overwriteAction = actions.stream().filter(a -> "OVERWRITE".equalsIgnoreCase(a.action())).findFirst().orElse(null);
        if (overwriteAction != null) return overwriteAction.content();

        AiResponseAction createAction = actions.stream().filter(a -> "CREATE".equalsIgnoreCase(a.action())).findFirst().orElse(null);
        if (createAction != null) return createAction.content();

        // 使用不保留末尾空行的拆分，避免行数+1 的“虚拟行”问题
        List<String> lines = splitToLines(originalContent);

        // 按行号倒序排序操作，这样在修改时不会影响前面操作的行索引
        actions.sort(Comparator.comparingInt((AiResponseAction a) -> ChangeApplierService.getInstance(project).getActionStartLine(a)).reversed());

        for (AiResponseAction action : actions) {
            try {
                switch (action.action().toUpperCase()) {
                    case "UPDATE": {
                        int start0 = action.startLine() != null ? action.startLine() - 1 : 0;
                        int end0 = action.endLine() != null ? action.endLine() - 1 : start0;

                        if (lines.isEmpty()) {
                            // 空文件场景：直接把内容视为整文件替换
                            lines.addAll(splitToLines(action.content()));
                            break;
                        }

                        // 对行号进行夹取，确保在有效范围内
                        start0 = clamp(start0, 0, lines.size() - 1);
                        end0 = clamp(end0, start0, lines.size() - 1);

                        for (int i = end0; i >= start0; i--) {
                            lines.remove(i);
                        }
                        lines.addAll(start0, splitToLines(action.content()));
                        break;
                    }
                    case "INSERT": {
                        int line0 = action.line() != null ? action.line() - 1 : lines.size();
                        // 允许在最后一行之后插入（line == size）
                        line0 = clamp(line0, 0, lines.size());
                        lines.addAll(line0, splitToLines(action.content()));
                        break;
                    }
                    case "DELETE": {
                        if (action.startLine() != null && action.endLine() != null) {
                            if (lines.isEmpty()) break;
                            int start0 = clamp(action.startLine() - 1, 0, lines.size() - 1);
                            int end0 = clamp(action.endLine() - 1, start0, lines.size() - 1);
                            for (int i = end0; i >= start0; i--) {
                                lines.remove(i);
                            }
                        } else {
                            // 删除整个文件
                            lines.clear();
                        }
                        break;
                    }
                    default:
                        // 其他操作类型（如未知类型）忽略
                        break;
                }
            } catch (Exception e) {
                // 预览阶段忽略异常，避免影响 UI 体验
            }
        }

        // 将行列表重新组合成一个用 \n 分隔的字符串
        return String.join("\n", lines);
    }

    // 将文本拆分为行，不保留末尾空行，避免越界问题
    private List<String> splitToLines(String text) {
        if (text == null || text.isEmpty()) return new ArrayList<>();
        return new ArrayList<>(Arrays.asList(text.split("\\R")));
    }

    // 将值限制在 [min, max] 区间内
    private int clamp(int val, int min, int max) {
        return Math.max(min, Math.min(max, val));
    }

    private record DiffData(VirtualFile file, String originalContent) {}
}
