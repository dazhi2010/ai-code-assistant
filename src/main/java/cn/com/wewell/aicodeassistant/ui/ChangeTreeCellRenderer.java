package cn.com.wewell.aicodeassistant.ui;

import com.intellij.icons.AllIcons;
import com.intellij.ui.ColoredTreeCellRenderer;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;

public class ChangeTreeCellRenderer extends ColoredTreeCellRenderer {
    @Override
    public void customizeCellRenderer(@NotNull JTree tree, Object value, boolean selected, boolean expanded, boolean leaf, int row, boolean hasFocus) {
        if (value instanceof DefaultMutableTreeNode) {
            Object userObject = ((DefaultMutableTreeNode) value).getUserObject();
            if (leaf) {
                // 叶子节点（具体操作）
                setIcon(AllIcons.Actions.Diff);
            } else {
                // 父节点（文件名）
                setIcon(AllIcons.FileTypes.Any_type);
            }
            append(value.toString());
        }
    }
}