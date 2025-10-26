package cn.com.wewell.aicodeassistant.ui;

import cn.com.wewell.aicodeassistant.model.AiResponseAction;
import cn.com.wewell.aicodeassistant.model.RequiredArtifact;
import com.intellij.icons.AllIcons;
import com.intellij.ui.ColoredTreeCellRenderer;
import com.intellij.ui.SimpleTextAttributes;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import java.util.Objects;

public class ChangeTreeCellRenderer extends ColoredTreeCellRenderer {
    @Override
    public void customizeCellRenderer(@NotNull JTree tree, Object value, boolean selected, boolean expanded, boolean leaf, int row, boolean hasFocus) {
        // 重置 tooltip，避免旧的 tooltip 残留
        setToolTipText(null);

        if (value instanceof DefaultMutableTreeNode) {
            Object userObject = ((DefaultMutableTreeNode) value).getUserObject();

            if (userObject instanceof AiResponseAction action) {
                // 叶子节点（具体操作）
                setIcon(AllIcons.Actions.Diff);

                // 1. 显示操作类型
                append(action.action().toUpperCase(), SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES);
                append(" ");

                // 2. 添加上下文信息（不再显示文件路径）
                if (action.oldCodeBlock() != null && !action.oldCodeBlock().isEmpty()) {
                    String oldBlockSnippet = action.oldCodeBlock().trim().split("\\R")[0];
                    if (oldBlockSnippet.length() > 50) {
                        oldBlockSnippet = oldBlockSnippet.substring(0, 47) + "...";
                    }
                    append(" (匹配: \"" + oldBlockSnippet + "\")", SimpleTextAttributes.GRAY_ATTRIBUTES);
                } else if (action.content() != null && !action.content().isEmpty()) {
                    String contentSnippet = action.content().trim().split("\\R")[0];
                    if (contentSnippet.length() > 50) {
                        contentSnippet = contentSnippet.substring(0, 47) + "...";
                    }
                    append(" (内容: \"" + contentSnippet + "\")", SimpleTextAttributes.GRAY_ATTRIBUTES);
                }

                // 3. 设置 Tooltip 显示新代码
                String newCode = action.newCodeBlock() != null ? action.newCodeBlock() : action.content();
                if (newCode != null && !newCode.isEmpty()) {
                    // 使用 HTML 和 <pre> 标签来保持格式，并转义HTML特殊字符
                    String escapedCode = newCode.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
                    setToolTipText("<html><pre>" + escapedCode + "</pre></html>");
                }

            } else if (userObject instanceof RequiredArtifact req) {
                if (req.type() == RequiredArtifact.Type.FILE) {
                    setIcon(AllIcons.FileTypes.Any_type);
                    append("需要文件: ", SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES);
                    append(req.nameOrPath(), SimpleTextAttributes.SIMPLE_CELL_ATTRIBUTES);
                } else {
                    setIcon(AllIcons.Nodes.Class);
                    append("需要类: ", SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES);
                    append(req.nameOrPath(), SimpleTextAttributes.SIMPLE_CELL_ATTRIBUTES);
                }
            } else if (leaf) {
                // 其他叶子节点的备用处理
                setIcon(AllIcons.Actions.Diff);
                append(Objects.toString(userObject));
            } else {
                // 父节点（文件名）
                setIcon(AllIcons.FileTypes.Any_type);
                append(Objects.toString(userObject)); // 显示文件路径
            }
        }
    }
}