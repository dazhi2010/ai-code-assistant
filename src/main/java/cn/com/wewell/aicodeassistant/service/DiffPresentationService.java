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
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.fileTypes.PlainTextFileType;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.vfs.VirtualFile;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service(Service.Level.PROJECT)
public final class DiffPresentationService {

    private final Project project;

    // 仅允许一个预览任务并可取消
    private final java.util.concurrent.atomic.AtomicReference<ProgressIndicator> runningPreview = new java.util.concurrent.atomic.AtomicReference<>();
    private final java.util.concurrent.atomic.AtomicLong previewSequence = new java.util.concurrent.atomic.AtomicLong(0);

    // 预览安全阈值
    private static final int PREVIEW_MAX_CHARS = 700_000;      // 预览文本超过则降级
    private static final int PREVIEW_TIME_BUDGET_MS = 3000;    // 3 秒时间预算

    public DiffPresentationService(Project project) {
        this.project = project;
    }

    public static DiffPresentationService getInstance(Project project) {
        return project.getService(DiffPresentationService.class);
    }

    public void showDiffForFile(List<AiResponseAction> actionsForFile) {
        if (actionsForFile == null || actionsForFile.isEmpty()) return;
        String filePath = actionsForFile.get(0).filePath();

        // 取消上一个任务，避免堆积
        ProgressIndicator prev = runningPreview.getAndSet(null);
        if (prev != null) prev.cancel();
        long seq = previewSequence.incrementAndGet();

        ProgressManager.getInstance().run(new Task.Backgroundable(project, "构建差异预览", true) {
            private SimpleDiffRequest request;
            private ProgressIndicator myIndicator;

            @Override
            public void run(@org.jetbrains.annotations.NotNull ProgressIndicator indicator) {
                myIndicator = indicator;
                runningPreview.set(indicator);
                indicator.setIndeterminate(true);
                indicator.setText("读取原始文件");

                long startNs = System.nanoTime();

                // 1) 读取原始文件内容（ReadAction）
                DiffData diffData = ApplicationManager.getApplication().runReadAction(
                        (Computable<DiffData>) () -> {
                            VirtualFile f = ChangeApplierService.getInstance(project).findVirtualFile(filePath);
                            String originalContent = "";
                            if (f != null && f.exists()) {
                                Document doc = FileDocumentManager.getInstance().getDocument(f);
                                if (doc != null) originalContent = doc.getText();
                            }
                            return new DiffData(f, originalContent);
                        }
                );

                if (indicator.isCanceled()) return;
                indicator.setText("生成预览内容");

                // 2) 构建右侧预览文本（仅精确匹配，避免重型正则）
                String previewContent = createPreviewContentByStringManipulation(diffData.originalContent, actionsForFile, indicator);

                // 3) 超大/超时降级为纯文本，避免语法高亮耗时
                boolean degrade = isTooBig(diffData.originalContent) || isTooBig(previewContent) || (elapsedMs(startNs) > PREVIEW_TIME_BUDGET_MS);
                FileType ft = degrade || diffData.file == null ? PlainTextFileType.INSTANCE : diffData.file.getFileType();

                if (indicator.isCanceled()) return;
                indicator.setText("构建 Diff 视图");

                // 4) 构建 DiffContent（ReadAction 更稳妥）
                DiffContent content1 = ApplicationManager.getApplication().runReadAction(
                        (Computable<DiffContent>) () -> DiffContentFactory.getInstance().create(project, diffData.originalContent, ft)
                );
                DiffContent content2 = ApplicationManager.getApplication().runReadAction(
                        (Computable<DiffContent>) () -> DiffContentFactory.getInstance().create(project, previewContent, ft)
                );

                String title = "聚合变更预览: " + filePath;
                request = new SimpleDiffRequest(title, content1, content2, "当前内容", "AI 生成的最终效果");

                List<AnAction> actions = new ArrayList<>();
                actions.add(new ApplyChangeAction(actionsForFile));
                request.putUserData(DiffUserDataKeys.CONTEXT_ACTIONS, actions);
                if (degrade) {
                    request.putUserData(DiffUserDataKeys.PLACE, "预览已降级为纯文本以避免性能问题");
                }
            }

            @Override
            public void onSuccess() {
                if (myIndicator != null && myIndicator.isCanceled()) return;
                if (seq != previewSequence.get()) return; // 过期任务不显示
                if (request != null) {
                    DiffManager.getInstance().showDiff(project, request);
                }
            }

            @Override
            public void onFinished() {
                runningPreview.compareAndSet(myIndicator, null);
            }

            @Override
            public void onCancel() {
                runningPreview.compareAndSet(myIndicator, null);
            }
        });
    }

    private String createPreviewContentByStringManipulation(String originalContent, List<AiResponseAction> actions, ProgressIndicator indicator) {
        // 优先处理 OVERWRITE / CREATE（直接决定全部内容）
        AiResponseAction overwriteAction = actions.stream().filter(a -> "OVERWRITE".equalsIgnoreCase(a.action())).findFirst().orElse(null);
        if (overwriteAction != null) return overwriteAction.content() != null ? overwriteAction.content().replace("\r\n", "\n").replace("\r", "\n") : "";
        AiResponseAction createAction = actions.stream().filter(a -> "CREATE".equalsIgnoreCase(a.action())).findFirst().orElse(null);
        if (createAction != null) return createAction.content() != null ? createAction.content().replace("\r\n", "\n").replace("\r", "\n") : "";

        String currentStr = originalContent == null ? "" : originalContent;
        ChangeApplierService applier = ChangeApplierService.getInstance(project);

        long deadline = System.nanoTime() + PREVIEW_TIME_BUDGET_MS * 1_000_000L; // 软超时，避免卡住
        for (AiResponseAction action : actions) {
            if (indicator != null) indicator.checkCanceled();
            if (System.nanoTime() > deadline) break; // 超时则中止，展示部分预览

            String type = action.action() != null ? action.action().toUpperCase() : "";
            if ("UPDATE".equals(type) || "INSERT_AFTER".equals(type) || "DELETE".equals(type)) {
                try {
                    // 复用 ChangeApplierService 强大且一致的匹配逻辑
                    currentStr = applier.applyChangeToContent(currentStr, action);
                } catch (Exception e) {
                    // 预览阶段如果某项匹配失败，忽略该项，继续展示其他已匹配的变更
                }
            }
        }
        return currentStr;
    }

    private static boolean isTooBig(String s) { return s != null && s.length() > PREVIEW_MAX_CHARS; }
    private static int elapsedMs(long startNs) { return (int)((System.nanoTime() - startNs) / 1_000_000L); }

    private record DiffData(VirtualFile file, String originalContent) {}
}
