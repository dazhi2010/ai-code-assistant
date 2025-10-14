package cn.com.wewell.aicodeassistant.action.toolwindow;

import cn.com.wewell.aicodeassistant.service.PromptManager;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileTypes.FileTypeRegistry;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * @author yuqf
 */
public class GenerateProjectTreeAction extends AnAction {

    private static final Set<String> IGNORED_DIRS = new HashSet<>(Arrays.asList(
            ".git", ".idea", ".gradle", "build", "target", "out", "node_modules", ".run"
    ));

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) {
            return;
        }

        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            StringBuilder treeBuilder = new StringBuilder("## 项目目录结构\n");
            treeBuilder.append("`\n");

            VirtualFile baseDir = project.getBaseDir();
            if (baseDir != null) {
                buildTree(baseDir, "", true, treeBuilder);
            }

            treeBuilder.append("`\n\n");

            ApplicationManager.getApplication().invokeLater(() -> {
                PromptManager.getInstance(project).addText(treeBuilder.toString());
            });
        });
    }

    private void buildTree(VirtualFile file, String indent, boolean isLast, StringBuilder treeBuilder) {
        if (isIgnored(file)) {
            return;
        }

        treeBuilder.append(indent);
        if (isLast) {
            treeBuilder.append("└── ");
            indent += "    ";
        } else {
            treeBuilder.append("├── ");
            indent += "│   ";
        }
        treeBuilder.append(file.getName()).append("\n");

        if (file.isDirectory()) {
            VirtualFile[] children = file.getChildren();
            for (int i = 0; i < children.length; i++) {
                buildTree(children[i], indent, i == children.length - 1, treeBuilder);
            }
        }
    }

    private boolean isIgnored(VirtualFile file) {
        String name = file.getName();
        if (name.startsWith(".")) {
            return true;
        }
        if (file.isDirectory() && IGNORED_DIRS.contains(name)) {
            return true;
        }
        return FileTypeRegistry.getInstance().isFileIgnored(file);
    }
}
