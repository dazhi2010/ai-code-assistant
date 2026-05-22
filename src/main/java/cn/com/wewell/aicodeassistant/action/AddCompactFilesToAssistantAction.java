package cn.com.wewell.aicodeassistant.action;

import cn.com.wewell.aicodeassistant.common.Constants;
import cn.com.wewell.aicodeassistant.service.AssistantConfigService;
import cn.com.wewell.aicodeassistant.service.PromptManager;
import cn.com.wewell.aicodeassistant.util.CompactContentBuilder;
import cn.com.wewell.aicodeassistant.util.PromptContentUtil;
import cn.com.wewell.aicodeassistant.util.TokenBudgeter;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.*;
import com.intellij.util.containers.ContainerUtil;
import org.jetbrains.annotations.NotNull;

import java.util.*;

/**
 * 以“紧凑模式”将选中文件/目录添加到大鱼AI编程助手。
 * - Java：仅签名与结构，方法体省略
 * - JS/TS/HTML/CSS：体省略或最小化
 * - 自动按 token 预算截断，防止超限
 */
public class AddCompactFilesToAssistantAction extends AnAction implements DumbAware {

    public AddCompactFilesToAssistantAction() {
        super("添加到大鱼AI编程助手（紧凑）", "以紧凑模式添加选中文件/目录到大鱼AI编程助手", null);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) return;
        VirtualFile[] selected = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY);
        if (selected == null || selected.length == 0) return;

        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            AssistantConfigService config = AssistantConfigService.getInstance(project);
            config.ensureDefaultConfig();

            // 收集可处理文件
            List<VirtualFile> files = new ArrayList<>();
            for (VirtualFile vf : selected) {
                if (vf.isDirectory()) {
                    VirtualFileFilter dirFilter = f -> !f.isDirectory() || !config.isIgnored(f);
                    VfsUtilCore.iterateChildrenRecursively(vf, dirFilter, child -> {
                        if (!child.isDirectory() && PromptContentUtil.isProcessable(child) && !config.isIgnored(child)) {
                            files.add(child);
                        }
                        return true;
                    });
                } else if (PromptContentUtil.isProcessable(vf) && !config.isIgnored(vf)) {
                    files.add(vf);
                }
            }

            if (files.isEmpty()) return;

            // 构建紧凑内容，按 token 预算截断
            StringBuilder payload = new StringBuilder();
            PromptManager pm = PromptManager.getInstance(project);
            String current = pm.getInputContent();
            int limit = Constants.DEFAULT_TOKEN_LIMIT; // 默认上限
            int used = TokenBudgeter.estimateTokens(current);

            // 排序：优先小文件，尽量多塞一点上下文
            files.sort(Comparator.comparingLong(VirtualFile::getLength));

            List<VirtualFile> accepted = new ArrayList<>();
            List<VirtualFile> omitted = new ArrayList<>();

            for (VirtualFile f : files) {
                String rel = VfsUtil.getRelativePath(f, project.getBaseDir(), '/');
                String lang = PromptContentUtil.getFileTypeMarkdown(f);
                String compact = CompactContentBuilder.buildCompact(project, f);

                String block = "## 文件路径: " + rel + "\n" +
                        "```" + lang + "\n" + compact + "\n```\n\n";

                int need = TokenBudgeter.estimateTokens(block);
                if (used + need > limit) {
                    omitted.add(f);
                    continue;
                }
                payload.append(block);
                used += need;
                accepted.add(f);
            }

            // 末尾补“我的需求”段
            if (!current.contains("## 我的需求")) {
                payload.append("## 我的需求\n[请在这里补充您的需求描述...]");
            }

            if (payload.length() == 0) return;

            ApplicationManager.getApplication().invokeLater(() -> {
                pm.addText(payload.toString());
            });
        });
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        VirtualFile[] files = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY);
        e.getPresentation().setEnabledAndVisible(project != null && files != null && files.length > 0);
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }
}
