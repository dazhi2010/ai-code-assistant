package cn.com.wewell.aicodeassistant.action.tree;

import cn.com.wewell.aicodeassistant.model.AiResponseAction;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.project.DumbAware;
import org.jetbrains.annotations.NotNull;

import javax.swing.tree.DefaultMutableTreeNode;
import java.awt.datatransfer.StringSelection;

public class CopyOldContentAction extends AnAction implements DumbAware {

    private final DefaultMutableTreeNode node;

    public CopyOldContentAction(DefaultMutableTreeNode node) {
        super("复制目标内容（oldCodeBlock）");
        this.node = node;
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        if (!(node.getUserObject() instanceof AiResponseAction action)) return;
        String old = action.oldCodeBlock();
        if (old != null && !old.isEmpty()) {
            CopyPasteManager.getInstance().setContents(new StringSelection(old));
        }
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        boolean visible = false;
        if (node.getUserObject() instanceof AiResponseAction action) {
            String old = action.oldCodeBlock();
            // UPDATE / INSERT_AFTER / DELETE 通常会有 oldCodeBlock
            visible = old != null && !old.isEmpty();
        }
        e.getPresentation().setEnabledAndVisible(visible);
    }
}