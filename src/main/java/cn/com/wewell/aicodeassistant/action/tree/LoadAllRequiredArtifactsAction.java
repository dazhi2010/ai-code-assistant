package cn.com.wewell.aicodeassistant.action.tree;

import cn.com.wewell.aicodeassistant.common.Constants;
import cn.com.wewell.aicodeassistant.service.PromptManager;
import cn.com.wewell.aicodeassistant.service.ChangeApplierService;
import cn.com.wewell.aicodeassistant.util.PromptContentUtil;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiFile;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.PsiShortNamesCache;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 中文注释：一键加载 requires 中所有文件/类到工作区。
 * - 自动去重（files 与 classes 不重复，文件优先）。
 * - 仅在一次调用中 clear 并写入，避免逐项清空导致只保留最后一个。
 */
public class LoadAllRequiredArtifactsAction extends AnAction implements DumbAware {
    private final Project project;

    public LoadAllRequiredArtifactsAction(Project project) {
        super("加载全部所需上下文");
        this.project = project;
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        loadAll();
    }

    /**
     * 中文注释：对外公开，便于测试或其他入口直接调用
     */
    public void loadAll() {
        if (project == null) return;
        PromptManager pm = PromptManager.getInstance(project);
        if (pm == null) return;

        // 读取并去重
        LinkedHashSet<String> fileSet = new LinkedHashSet<>();
        List<String> reqFiles = pm.getRequiredFiles();
        if (reqFiles != null) {
            for (String f : reqFiles) {
                if (f != null) {
                    String norm = f.trim().replace('\\', '/');
                    if (!norm.isBlank()) fileSet.add(norm);
                }
            }
        }

        LinkedHashSet<String> classSet = new LinkedHashSet<>();
        List<String> reqClasses = pm.getRequiredClasses();
        if (reqClasses != null) {
            for (String c : reqClasses) {
                if (c != null) {
                    String norm = c.trim();
                    if (!norm.isBlank()) classSet.add(norm);
                }
            }
        }

        // 跨类型去重（基于文件名与类的简单名，文件优先）
        Set<String> fileBase = new HashSet<>();
        for (String p : fileSet) {
            int slash = p.lastIndexOf('/');
            String name = slash >= 0 ? p.substring(slash + 1) : p;
            int dot = name.lastIndexOf('.');
            String base = dot >= 0 ? name.substring(0, dot) : name;
            fileBase.add(base.toLowerCase(Locale.ROOT));
        }
        classSet.removeIf(fqn -> {
            int dot = fqn.lastIndexOf('.');
            String simple = dot >= 0 ? fqn.substring(dot + 1) : fqn;
            return fileBase.contains(simple.toLowerCase(Locale.ROOT));
        });

        List<VirtualFile> toLoad = new ArrayList<>();

        // 解析文件路径
        for (String path : fileSet) {
            VirtualFile vf = ChangeApplierService.getInstance(project).findVirtualFile(path);
            if (vf != null && vf.exists()) {
                toLoad.add(vf);
            }
        }

        // 解析类到对应文件
        GlobalSearchScope scope = GlobalSearchScope.projectScope(project);
        JavaPsiFacade jpf = JavaPsiFacade.getInstance(project);
        for (String fqn : classSet) {
            PsiClass psiClass = jpf.findClass(fqn, scope);
            if (psiClass == null) {
                String simple = fqn.lastIndexOf('.') >= 0 ? fqn.substring(fqn.lastIndexOf('.') + 1) : fqn;
                PsiClass[] candidates = PsiShortNamesCache.getInstance(project).getClassesByName(simple, scope);
                if (candidates.length > 0) psiClass = candidates[0];
            }
            if (psiClass != null) {
                PsiFile psiFile = psiClass.getContainingFile();
                if (psiFile != null && psiFile.getVirtualFile() != null) {
                    toLoad.add(psiFile.getVirtualFile());
                }
            }
        }

        if (toLoad.isEmpty()) {
            NotificationGroupManager.getInstance().getNotificationGroup(Constants.NOTIFICATION_GROUP_ID)
                    .createNotification("未找到所需的文件/类，请确认 requires 列表是否正确。", NotificationType.WARNING)
                    .notify(project);
            return;
        }

        // 一次性写入，避免多次清空
        PromptContentUtil.addFilesToPrompt(project, toLoad, true, true);
    }
}
