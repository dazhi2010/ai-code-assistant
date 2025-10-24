package cn.com.wewell.aicodeassistant.ui;

import cn.com.wewell.aicodeassistant.model.AiResponseAction;
import cn.com.wewell.aicodeassistant.model.FileChanges;
import cn.com.wewell.aicodeassistant.service.DiffPresentationService;
import cn.com.wewell.aicodeassistant.service.HistoryManager;
import cn.com.wewell.aicodeassistant.service.PromptManager;
import com.intellij.json.JsonFileType;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionPopupMenu;
import com.intellij.openapi.actionSystem.ActionToolbar;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.event.DocumentEvent;
import com.intellij.openapi.editor.event.DocumentListener;
import com.intellij.openapi.editor.ex.EditorEx;
import com.intellij.openapi.fileTypes.FileTypes;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.SimpleToolWindowPanel;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.EditorTextField;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTabbedPane;
import com.intellij.ui.components.JBTextField;
import com.intellij.ui.treeStructure.Tree;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class AssistantToolWindow extends SimpleToolWindowPanel {

    private static final String JSON_INPUT_CARD = "JSON_INPUT_CARD";
    private static final String CHANGES_PREVIEW_CARD = "CHANGES_PREVIEW_CARD";

    private final Project project;
    private final EditorTextField inputArea;
    private final EditorTextField outputJsonArea;
    private final Tree changesTree;
    private final JPanel rightPanel;
    private final CardLayout rightCardLayout;
    private final JBTextField themeField;
    private final JBList<VirtualFile> historyList;

    public AssistantToolWindow(Project project) {
        super(true, true);
        this.project = project;

        // --- Toolbar ---
        JPanel toolbarPanel = new JPanel(new BorderLayout());
        ActionManager actionManager = ActionManager.getInstance();
        DefaultActionGroup actionGroup = (DefaultActionGroup) actionManager.getAction("AIAssistant.ToolWindowActions");
        ActionToolbar actionToolbar = actionManager.createActionToolbar("AIAssistantToolbar", actionGroup, true);
        actionToolbar.setTargetComponent(this);

        // --- 恢复的、正确的初始化代码 ---
        themeField = new JBTextField("默认主题");
        JPanel themePanel = new JPanel(new BorderLayout());
        themePanel.setBorder(JBUI.Borders.emptyLeft(10));
        themePanel.add(new JLabel("主题: "), BorderLayout.WEST);
        themePanel.add(themeField, BorderLayout.CENTER);
        themePanel.setPreferredSize(new Dimension(200, themePanel.getPreferredSize().height));

        toolbarPanel.add(actionToolbar.getComponent(), BorderLayout.CENTER);
        toolbarPanel.add(themePanel, BorderLayout.EAST);
        setToolbar(toolbarPanel);
        // ---------------------------------

        // --- 主工作区 (Tabbed Pane) ---
        JBTabbedPane tabbedPane = new JBTabbedPane();

        // Tab 1: 主工作区
        JPanel mainPanel = new JPanel(new BorderLayout());
        inputArea = new EditorTextField(EditorFactory.getInstance().createDocument(""), project, FileTypes.PLAIN_TEXT, false, true);
        inputArea.setOneLineMode(false);
        // 中文注释：通过 SettingsProvider 关闭横向滚动条并开启自动换行（确保在编辑器创建后生效）
        inputArea.addSettingsProvider((EditorEx editor) -> {
            editor.getSettings().setUseSoftWraps(true);
            editor.getSettings().setWrapWhenTypingReachesRightMargin(true);
            editor.setHorizontalScrollbarVisible(false);
        });
        if (inputArea.getEditor() != null) {
            inputArea.getEditor().getSettings().setUseSoftWraps(true); // 兜底：有时构造阶段已创建编辑器
        }

        rightCardLayout = new CardLayout();
        rightPanel = new JPanel(rightCardLayout);

        outputJsonArea = new EditorTextField(EditorFactory.getInstance().createDocument(""), project, JsonFileType.INSTANCE, false, true);
        outputJsonArea.setOneLineMode(false);
        // 中文注释：同样优化 JSON 输出区的编辑器滚动条与自动换行
        outputJsonArea.addSettingsProvider((EditorEx editor) -> {
            editor.getSettings().setUseSoftWraps(true);
            editor.getSettings().setWrapWhenTypingReachesRightMargin(true);
            editor.setHorizontalScrollbarVisible(false);
        });
        if (outputJsonArea.getEditor() != null) {
            outputJsonArea.getEditor().getSettings().setUseSoftWraps(true);
        }

        // 中文注释：右侧 JSON 输入卡片使用 JBScrollPane，但强制关闭横向滚动条
        JBScrollPane jsonScroll = new JBScrollPane(outputJsonArea);
        jsonScroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        rightPanel.add(jsonScroll, JSON_INPUT_CARD);

        changesTree = new Tree(new DefaultMutableTreeNode("变更摘要"));
        changesTree.setCellRenderer(new ChangeTreeCellRenderer());
        rightPanel.add(new JBScrollPane(changesTree), CHANGES_PREVIEW_CARD);

        // 中文注释：左侧输入区同样关闭横向滚动条
        JBScrollPane inputScroll = new JBScrollPane(inputArea);
        inputScroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);

        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, inputScroll, rightPanel);
        splitPane.setResizeWeight(0.5);
        mainPanel.add(splitPane, BorderLayout.CENTER);
        tabbedPane.addTab("工作区", mainPanel);

        // Tab 2: 历史记录
        historyList = new JBList<>();
        historyList.setCellRenderer(new HistoryListCellRenderer());
        JPanel historyPanel = new JPanel(new BorderLayout());
        historyPanel.add(new JBScrollPane(historyList), BorderLayout.CENTER);
        tabbedPane.addTab("历史记录", historyPanel);

        setContent(tabbedPane);

        // --- 绑定服务和UI ---
        PromptManager promptManager = PromptManager.getInstance(project);
        promptManager.setInputListener(text -> {
            if (!inputArea.getText().equals(text)) {
                ApplicationManager.getApplication().runWriteAction(() -> inputArea.setText(text));
            }
        });
        promptManager.setOutputListener(text -> {
            if (!outputJsonArea.getText().equals(text)) {
                ApplicationManager.getApplication().runWriteAction(() -> outputJsonArea.setText(text));
            }
        });
        inputArea.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void documentChanged(@NotNull DocumentEvent event) {
                promptManager.syncInputContent(event.getDocument().getText());
            }
        });
        // 为右侧输出区添加监听器，实现双向绑定
        outputJsonArea.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void documentChanged(@NotNull DocumentEvent event) {
                promptManager.syncOutputContent(event.getDocument().getText());
            }
        });
        // 添加双击事件监听器
        changesTree.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (SwingUtilities.isRightMouseButton(e)) {
                    int row = changesTree.getRowForLocation(e.getX(), e.getY());
                    if (row != -1) {
                        changesTree.setSelectionRow(row);
                        DefaultMutableTreeNode node = (DefaultMutableTreeNode) changesTree.getLastSelectedPathComponent();
                        if (node != null && node.isRoot()) {
                            // 只有在根节点上右键才显示菜单
                            PromptManager promptManager = PromptManager.getInstance(project);
                            List<AiResponseAction> allActions = promptManager.getParsedActions();

                            DefaultActionGroup group = new DefaultActionGroup();
                            group.add(new cn.com.wewell.aicodeassistant.action.tree.ApplyAllChangesAction(allActions));

                            ActionPopupMenu popupMenu = ActionManager.getInstance().createActionPopupMenu("AIAssistantTreePopup", group);
                            popupMenu.getComponent().show(e.getComponent(), e.getX(), e.getY());
                        }
                    }
                }
            }

            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    DefaultMutableTreeNode node = (DefaultMutableTreeNode) changesTree.getLastSelectedPathComponent();
                    if (node == null) return;

                    if (node.isLeaf()) {
                        node = (DefaultMutableTreeNode) node.getParent();
                    }

                    if (node != null && node.getUserObject() instanceof FileChanges) {
                        FileChanges fileChanges = (FileChanges) node.getUserObject();
                        if (!fileChanges.getActions().isEmpty()) {
                            DiffPresentationService.getInstance(project).showDiffForFile(fileChanges.getActions());
                        }
                    }
                }
            }
        });
        // --- 事件监听 ---
        tabbedPane.addChangeListener(e -> {
            if (tabbedPane.getSelectedIndex() == 1) {
                refreshHistoryList();
            }
        });

        // 双击历史记录项时，恢复内容
        historyList.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    VirtualFile selectedDir = historyList.getSelectedValue();
                    if (selectedDir != null) {
                        try {
                            VirtualFile requestFile = selectedDir.findChild("request.md");
                            VirtualFile responseFile = selectedDir.findChild("response.json");
                            if (requestFile != null && responseFile != null) {
                                String requestContent = VfsUtil.loadText(requestFile);
                                String responseContent = VfsUtil.loadText(responseFile);

                                // 注意：这里获取了新的 promptManager 实例，可能与上面的不是同一个
                                PromptManager pm = PromptManager.getInstance(project);
                                pm.syncInputContent(requestContent);
                                pm.setOutputContentAndNotify(responseContent);

                                tabbedPane.setSelectedIndex(0);
                            }
                        } catch (IOException ex) {
                            ex.printStackTrace();
                        }
                    }
                }
            }
        });
    }

    public void showChangesPreview(Map<String, List<AiResponseAction>> groupedActions) {
        DefaultMutableTreeNode root = new DefaultMutableTreeNode("变更摘要");
        for (Map.Entry<String, List<AiResponseAction>> entry : groupedActions.entrySet()) {
            // 使用 FileChanges 包装类
            FileChanges fileChanges = new FileChanges(entry.getKey(), entry.getValue());
            DefaultMutableTreeNode fileNode = new DefaultMutableTreeNode(fileChanges);

            for (AiResponseAction action : entry.getValue()) {
                // 直接将 action 对象作为 userObject
                DefaultMutableTreeNode actionNode = new DefaultMutableTreeNode(action);
                fileNode.add(actionNode);
            }
            root.add(fileNode);
        }
        changesTree.setModel(new DefaultTreeModel(root));
        // 展开所有节点
        for (int i = 0; i < changesTree.getRowCount(); i++) {
            changesTree.expandRow(i);
        }
        rightCardLayout.show(rightPanel, CHANGES_PREVIEW_CARD);
    }

    public void showJsonInputView() {
        rightCardLayout.show(rightPanel, JSON_INPUT_CARD);
    }



    public String getTheme() {
        return themeField.getText();
    }

    // 重新添加丢失的 syncInitialState 方法
    public void syncInitialState(String input, String output) {
        ApplicationManager.getApplication().runWriteAction(() -> {
            inputArea.setText(input);
            outputJsonArea.setText(output);
        });
    }

    private void refreshHistoryList() {
        String historyPath = project.getBasePath() + "/" + HistoryManager.HISTORY_DIR;
        VirtualFile historyDir = LocalFileSystem.getInstance().findFileByPath(historyPath);
        if (historyDir != null && historyDir.isDirectory()) {
            // 刷新以确保看到最新内容
            historyDir.refresh(false, true);
            VirtualFile[] children = historyDir.getChildren();
            // 按名称倒序排序，最新的在最上面
            Arrays.sort(children, (f1, f2) -> f2.getName().compareTo(f1.getName()));
            DefaultListModel<VirtualFile> model = new DefaultListModel<>();
            for (VirtualFile child : children) {
                if (child.isDirectory()) {
                    model.addElement(child);
                }
            }
            historyList.setModel(model);
        }
    }
}
