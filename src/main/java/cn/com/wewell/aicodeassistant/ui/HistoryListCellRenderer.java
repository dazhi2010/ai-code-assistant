package cn.com.wewell.aicodeassistant.ui;

import com.intellij.openapi.vfs.VirtualFile;

import javax.swing.*;
import java.awt.*;

public class HistoryListCellRenderer extends DefaultListCellRenderer {
    @Override
    public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
        super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
        if (value instanceof VirtualFile) {
            // 只显示目录名
            setText(((VirtualFile) value).getName());
            setIcon(com.intellij.icons.AllIcons.Actions.Menu_saveall);
        }
        return this;
    }
}