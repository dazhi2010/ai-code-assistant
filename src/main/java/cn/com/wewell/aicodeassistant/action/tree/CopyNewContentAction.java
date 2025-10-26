package cn.com.wewell.aicodeassistant.action.tree;

import cn.com.wewell.aicodeassistant.model.AiResponseAction;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.ide.CopyPasteManager;
import org.jetbrains.annotations.NotNull;

import javax.swing.tree.DefaultMutableTreeNode;
import java.awt.datatransfer.StringSelection;
import java.util.Objects;

public class CopyNewContentAction extends AnAction {

    private final DefaultMutableTreeNode node;

    public CopyNewContentAction(DefaultMutableTreeNode node) {
        super("复制新内容");
        this.node = node;
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        if (!(node.getUserObject() instanceof AiResponseAction action)) {
            return;
        }

        String contentToCopy = "";
        String actionType = action.action().toUpperCase();

        switch (actionType) {
            case "UPDATE":
            case "INSERT_AFTER":
                contentToCopy = action.newCodeBlock();
                break;
            case "CREATE":
            case "OVERWRITE":
                contentToCopy = action.content();
                break;
            case "DELETE":
                // 删除操作没有新内容可复制
                break;
        }

        if (contentToCopy != null && !contentToCopy.isEmpty()) {
            CopyPasteManager.getInstance().setContents(new StringSelection(contentToCopy));
        }
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        boolean visible = false;
        if (node.getUserObject() instanceof AiResponseAction action) {
            String actionType = action.action().toUpperCase();
            // 只有包含新内容的操作才显示此按钮
            if (Objects.equals(actionType, "UPDATE") || Objects.equals(actionType, "INSERT_AFTER") || Objects.equals(actionType, "CREATE") || Objects.equals(actionType, "OVERWRITE")) {
                visible = true;
            }
        }
        e.getPresentation().setEnabledAndVisible(visible);
    }
}
