package cn.com.wewell.aicodeassistant.action.tree;

import cn.com.wewell.aicodeassistant.model.RequiredArtifact;
import cn.com.wewell.aicodeassistant.service.ChangeApplierService;
import cn.com.wewell.aicodeassistant.util.PromptContentUtil;
import cn.com.wewell.aicodeassistant.common.Constants;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiFile;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.PsiShortNamesCache;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

public class LoadRequiredArtifactAction extends AnAction implements DumbAware {

    private final Project project;
    private final RequiredArtifact required;

    public LoadRequiredArtifactAction(Project project, RequiredArtifact required) {
        super("加载到工作区（清空后写入）");
        this.project = project;
        this.required = required;
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        load();
    }
    // 新增：公共方法，便于不通过 AnActionEvent 也能调用
    public void load() {
        if (project == null || required == null) return;

        List<VirtualFile> files = new ArrayList<>();

        if (required.type() == RequiredArtifact.Type.FILE) {
            VirtualFile vf = ChangeApplierService.getInstance(project).findVirtualFile(required.nameOrPath());
            if (vf != null && vf.exists()) {
                files.add(vf);
            }
        } else {
            String name = required.nameOrPath();
            GlobalSearchScope scope = GlobalSearchScope.projectScope(project);
            PsiClass psiClass = JavaPsiFacade.getInstance(project).findClass(name, scope);
            if (psiClass == null) {
                PsiClass[] candidates = PsiShortNamesCache.getInstance(project).getClassesByName(name, scope);
                if (candidates.length > 0) {
                    psiClass = candidates[0];
                }
            }
            if (psiClass != null) {
                PsiFile psiFile = psiClass.getContainingFile();
                if (psiFile != null && psiFile.getVirtualFile() != null) {
                    files.add(psiFile.getVirtualFile());
                }
            }
        }

        if (files.isEmpty()) {
            NotificationGroupManager.getInstance().getNotificationGroup(Constants.NOTIFICATION_GROUP_ID)
                    .createNotification("未找到所需" + (required.type() == RequiredArtifact.Type.FILE ? "文件: " : "类: ") + required.nameOrPath() +
                            "，请手动在工作区补充该代码。", NotificationType.WARNING)
                    .notify(project);
            return;
        }

        PromptContentUtil.addFilesToPrompt(project, files, true, true);
    }
}