package cn.com.wewell.aicodeassistant.ui;

import cn.com.wewell.aicodeassistant.model.AiResponseAction;
import cn.com.wewell.aicodeassistant.model.FileChanges;
import cn.com.wewell.aicodeassistant.model.RequiredArtifact;
import cn.com.wewell.aicodeassistant.service.DiffPresentationService;
import cn.com.wewell.aicodeassistant.service.HistoryManager;
import cn.com.wewell.aicodeassistant.service.PromptManager;
import com.intellij.json.JsonFileType;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.event.DocumentEvent;
import com.intellij.openapi.editor.event.DocumentListener;
import com.intellij.openapi.editor.ex.EditorEx;
import com.intellij.openapi.fileTypes.FileTypes;
import com.intellij.openapi.ide.CopyPasteManager;
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
import java.awt.datatransfer.StringSelection;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * AI 助手主窗口
 * @author yuqf
 */
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
    
    // 新增：AI 洞察展示区
    private final JTextArea insightArea;
    private final JPanel insightPanel;

    public AssistantToolWindow(Project project) {
        super(true, true);
        this.project = project;

        // --- Toolbar ---
        JPanel toolbarPanel = new JPanel(new BorderLayout());
        ActionManager actionManager = ActionManager.getInstance();
        DefaultActionGroup actionGroup = (DefaultActionGroup) actionManager.getAction("AIAssistant.ToolWindowActions");
        ActionToolbar actionToolbar = actionManager.createActionToolbar("AIAssistantToolbar", actionGroup, true);
        actionToolbar.setTargetComponent(this);

        themeField = new JBTextField("默认主题");
        JPanel themePanel = new JPanel(new BorderLayout());
        themePanel.setBorder(JBUI.Borders.emptyLeft(10));
        themePanel.add(new JLabel("主题: "), BorderLayout.WEST);
        themePanel.add(themeField, BorderLayout.CENTER);
        themePanel.setPreferredSize(new Dimension(200, themePanel.getPreferredSize().height));

        toolbarPanel.add(actionToolbar.getComponent(), BorderLayout.CENTER);
        toolbarPanel.add(themePanel, BorderLayout.EAST);
        setToolbar(toolbarPanel);

        // --- 主工作区 ---
        JBTabbedPane tabbedPane = new JBTabbedPane();

        JPanel mainPanel = new JPanel(new BorderLayout());
        inputArea = new EditorTextField(EditorFactory.getInstance().createDocument(""), project, FileTypes.PLAIN_TEXT, false, true);
        inputArea.setOneLineMode(false);
        inputArea.addSettingsProvider((EditorEx editor) -> {
            editor.getSettings().setUseSoftWraps(true);
            editor.getSettings().setWrapWhenTypingReachesRightMargin(true);
            editor.setHorizontalScrollbarVisible(false);
        });

        rightCardLayout = new CardLayout();
        rightPanel = new JPanel(rightCardLayout);

        outputJsonArea = new EditorTextField(EditorFactory.getInstance().createDocument(""), project, JsonFileType.INSTANCE, false, true);
        outputJsonArea.setOneLineMode(false);
        outputJsonArea.addSettingsProvider((EditorEx editor) -> {
            editor.getSettings().setUseSoftWraps(true);
            editor.getSettings().setWrapWhenTypingReachesRightMargin(true);
            editor.setHorizontalScrollbarVisible(false);
        });

        JBScrollPane jsonScroll = new JBScrollPane(outputJsonArea);
        jsonScroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        rightPanel.add(jsonScroll, JSON_INPUT_CARD);

        // 初始化 AI 洞察面板
        insightArea = new JTextArea();
        insightArea.setEditable(false);
        insightArea.setLineWrap(true);
        insightArea.setWrapStyleWord(true);
        insightArea.setBackground(JBUI.CurrentTheme.EditorTabs.background());
        insightArea.setFont(JBUI.Fonts.label().deriveFont(12f));
        insightArea.setBorder(JBUI.Borders.empty(5));
        
        JBScrollPane insightScroll = new JBScrollPane(insightArea);
        insightScroll.setBorder(JBUI.Borders.empty());

        insightPanel = new JPanel(new BorderLayout());
        insightPanel.setBackground(insightArea.getBackground());
        insightPanel.setBorder(JBUI.Borders.customLine(com.intellij.ui.JBColor.border(), 0, 0, 1, 0));
        
        // 添加折叠功能
        JPanel insightHeader = new JPanel(new BorderLayout());
        insightHeader.setBackground(JBUI.CurrentTheme.EditorTabs.background());
        insightHeader.setBorder(JBUI.Borders.empty(2, 5));
        JLabel titleLabel = new JLabel("思路与反馈");
        titleLabel.setFont(JBUI.Fonts.label().asBold());
        JButton toggleBtn = new JButton(com.intellij.icons.AllIcons.General.ArrowDown);
        toggleBtn.setBorderPainted(false);
        toggleBtn.setContentAreaFilled(false);
        toggleBtn.setFocusPainted(false);
        toggleBtn.setMargin(JBUI.emptyInsets());
        
        insightHeader.add(titleLabel, BorderLayout.WEST);
        insightHeader.add(toggleBtn, BorderLayout.EAST);
        
        insightPanel.add(insightHeader, BorderLayout.NORTH);
        insightPanel.add(insightScroll, BorderLayout.CENTER);
        insightPanel.setVisible(false);

        // 初始化变更树
        changesTree = new Tree(new DefaultMutableTreeNode("变更摘要"));
        changesTree.setCellRenderer(new ChangeTreeCellRenderer());
        JBScrollPane treeScroll = new JBScrollPane(changesTree);
        
        JSplitPane previewSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, insightPanel, treeScroll);
        previewSplit.setDividerLocation(0.5);
        previewSplit.setResizeWeight(0.5);
        previewSplit.setBorder(JBUI.Borders.empty());

        toggleBtn.addActionListener(al -> {
            boolean isVisible = insightScroll.isVisible();
            insightScroll.setVisible(!isVisible);
            toggleBtn.setIcon(isVisible ? com.intellij.icons.AllIcons.General.ArrowRight : com.intellij.icons.AllIcons.General.ArrowDown);
            previewSplit.revalidate();
            if (!isVisible) {
                previewSplit.setDividerLocation(0.5);
            } else {
                previewSplit.setDividerLocation(insightHeader.getPreferredSize().height);
            }
        });

        JPanel previewContainer = new JPanel(new BorderLayout());
        previewContainer.add(previewSplit, BorderLayout.CENTER);
        
        rightPanel.add(previewContainer, CHANGES_PREVIEW_CARD);

        inputArea.setPlaceholder("在这里输入您的需求...");
        inputArea.addSettingsProvider((EditorEx editor) -> {
            editor.getSettings().setUseSoftWraps(true);
            editor.getSettings().setWrapWhenTypingReachesRightMargin(true);
            editor.setHorizontalScrollbarVisible(false);
            editor.setVerticalScrollbarVisible(true);
            editor.getSettings().setLineNumbersShown(false);
            editor.getSettings().setAdditionalLinesCount(2);
        });

        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, inputArea, rightPanel);
        splitPane.setResizeWeight(0.5);
        mainPanel.add(splitPane, BorderLayout.CENTER);
        tabbedPane.addTab("工作区", mainPanel);

        // 历史记录
        historyList = new JBList<>();
        historyList.setCellRenderer(new HistoryListCellRenderer());
        JPanel historyPanel = new JPanel(new BorderLayout());
        historyPanel.add(new JBScrollPane(historyList), BorderLayout.CENTER);
        tabbedPane.addTab("历史记录", historyPanel);

        setContent(tabbedPane);

        // --- 绑定服务 ---
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
        outputJsonArea.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void documentChanged(@NotNull DocumentEvent event) {
                promptManager.syncOutputContent(event.getDocument().getText());
            }
        });

        // 变更树监听
        changesTree.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (SwingUtilities.isRightMouseButton(e)) {
                    int row = changesTree.getRowForLocation(e.getX(), e.getY());
                    if (row != -1) {
                        changesTree.setSelectionRow(row);
                        DefaultMutableTreeNode node = (DefaultMutableTreeNode) changesTree.getLastSelectedPathComponent();
                        if (node == null) return;

                        if (node.isRoot()) {
                            DefaultActionGroup group = new DefaultActionGroup();
                            group.add(new cn.com.wewell.aicodeassistant.action.tree.ApplyAllChangesAction(promptManager.getParsedActions()));
                            group.add(new cn.com.wewell.aicodeassistant.action.tree.LoadAllRequiredArtifactsAction(project));
                            ActionPopupMenu popupMenu = ActionManager.getInstance().createActionPopupMenu("AIAssistantTreePopup", group);
                            popupMenu.getComponent().show(e.getComponent(), e.getX(), e.getY());
                            return;
                        }

                        Object uo = node.getUserObject();
                        if (uo instanceof String s && "所需补充".equals(s)) {
                            DefaultActionGroup group = new DefaultActionGroup();
                            group.add(new cn.com.wewell.aicodeassistant.action.tree.LoadAllRequiredArtifactsAction(project));
                            ActionPopupMenu popupMenu = ActionManager.getInstance().createActionPopupMenu("AIAssistantTreePopup.RequireRoot", group);
                            popupMenu.getComponent().show(e.getComponent(), e.getX(), e.getY());
                            return;
                        }

                        if (uo instanceof AiResponseAction action) {
                            DefaultActionGroup group = new DefaultActionGroup();
                            group.add(new cn.com.wewell.aicodeassistant.action.tree.CompareAction(node));
                            group.addSeparator();
                            group.add(new cn.com.wewell.aicodeassistant.action.tree.CopyNewContentAction(node));
                            group.add(new cn.com.wewell.aicodeassistant.action.tree.CopyOldContentAction(node));
                            group.addSeparator();
                            group.add(new AnAction("删除") {
                                @Override
                                public void actionPerformed(@NotNull AnActionEvent e1) {
                                    promptManager.getParsedActions().remove(action);
                                    
                                    DefaultMutableTreeNode parentNode = (DefaultMutableTreeNode) node.getParent();
                                    if (parentNode != null && parentNode.getUserObject() instanceof FileChanges fileChanges) {
                                        // 修复预览 Bug：同步从 FileChanges 列表中删除
                                        fileChanges.getActions().remove(action);
                                    }

                                    DefaultTreeModel model = (DefaultTreeModel) changesTree.getModel();
                                    model.removeNodeFromParent(node);
                                    if (parentNode != null && parentNode.getChildCount() == 0 && !parentNode.isRoot()) {
                                        model.removeNodeFromParent(parentNode);
                                    }
                                }
                            });
                            ActionPopupMenu popupMenu = ActionManager.getInstance().createActionPopupMenu("AIAssistantTreePopup.ActionNode", group);
                            popupMenu.getComponent().show(e.getComponent(), e.getX(), e.getY());
                            return;
                        }

                        if (uo instanceof RequiredArtifact req) {
                            DefaultActionGroup group = new DefaultActionGroup();
                            group.add(new cn.com.wewell.aicodeassistant.action.tree.LoadRequiredArtifactAction(project, req));
                            group.add(new AnAction("复制名称/路径") {
                                @Override
                                public void actionPerformed(@NotNull AnActionEvent e1) {
                                    CopyPasteManager.getInstance().setContents(new StringSelection(req.nameOrPath()));
                                }
                            });
                            ActionPopupMenu popupMenu = ActionManager.getInstance().createActionPopupMenu("AIAssistantTreePopup.RequireNode", group);
                            popupMenu.getComponent().show(e.getComponent(), e.getX(), e.getY());
                        }
                    }
                }
            }

            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    DefaultMutableTreeNode node = (DefaultMutableTreeNode) changesTree.getLastSelectedPathComponent();
                    if (node == null) return;

                    Object uo = node.getUserObject();
                    if (uo instanceof RequiredArtifact req) {
                        new cn.com.wewell.aicodeassistant.action.tree.LoadRequiredArtifactAction(project, req).load();
                        return;
                    }

                    if (node.isLeaf()) node = (DefaultMutableTreeNode) node.getParent();
                    if (node != null && node.getUserObject() instanceof FileChanges fileChanges) {
                        if (!fileChanges.getActions().isEmpty()) {
                            DiffPresentationService.getInstance(project).showDiffForFile(fileChanges.getActions());
                        }
                    }
                }
            }
        });

        tabbedPane.addChangeListener(e -> {
            if (tabbedPane.getSelectedIndex() == 1) refreshHistoryList();
        });

        historyList.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    VirtualFile selectedDir = historyList.getSelectedValue();
                    if (selectedDir != null) {
                        try {
                            VirtualFile reqFile = selectedDir.findChild("request.md");
                            VirtualFile resFile = selectedDir.findChild("response.json");
                            if (reqFile != null && resFile != null) {
                                promptManager.syncInputContent(VfsUtil.loadText(reqFile));
                                promptManager.setOutputContentAndNotify(VfsUtil.loadText(resFile));
                                tabbedPane.setSelectedIndex(0);
                            }
                        } catch (IOException ex) { ex.printStackTrace(); }
                    }
                }
            }
        });
    }

    public void showChangesPreview(Map<String, List<AiResponseAction>> groupedActions) {
        PromptManager pm = PromptManager.getInstance(project);
        
        // 刷新 AI 洞察区域
        StringBuilder insightText = new StringBuilder();
        if (pm.getAiThought() != null && !pm.getAiThought().isBlank()) {
            insightText.append("💡 思路: ").append(pm.getAiThought()).append("\n\n");
        }
        if (pm.getAiFeedback() != null && !pm.getAiFeedback().isBlank()) {
            insightText.append("📣 反馈: ").append(pm.getAiFeedback()).append("\n\n");
        }
        if (pm.getAiCommands() != null && !pm.getAiCommands().isEmpty()) {
            insightText.append("💻 命令: ").append(String.join("; ", pm.getAiCommands()));
        }
        
        String finalInsight = insightText.toString().trim();
        insightArea.setText(finalInsight);
        insightPanel.setVisible(!finalInsight.isEmpty());

        DefaultMutableTreeNode root = new DefaultMutableTreeNode("变更摘要");
        for (Map.Entry<String, List<AiResponseAction>> entry : groupedActions.entrySet()) {
            FileChanges fileChanges = new FileChanges(entry.getKey(), entry.getValue());
            DefaultMutableTreeNode fileNode = new DefaultMutableTreeNode(fileChanges);
            for (AiResponseAction action : entry.getValue()) {
                fileNode.add(new DefaultMutableTreeNode(action));
            }
            root.add(fileNode);
        }

        List<String> reqFiles = pm.getRequiredFiles();
        List<String> reqClasses = pm.getRequiredClasses();
        if ((reqFiles != null && !reqFiles.isEmpty()) || (reqClasses != null && !reqClasses.isEmpty())) {
            DefaultMutableTreeNode requireRoot = new DefaultMutableTreeNode("所需补充");
            if (reqFiles != null) {
                for (String path : reqFiles) requireRoot.add(new DefaultMutableTreeNode(new RequiredArtifact(RequiredArtifact.Type.FILE, path)));
            }
            if (reqClasses != null) {
                for (String cls : reqClasses) requireRoot.add(new DefaultMutableTreeNode(new RequiredArtifact(RequiredArtifact.Type.CLASS, cls)));
            }
            root.add(requireRoot);
        }

        changesTree.setModel(new DefaultTreeModel(root));
        for (int i = 0; i < changesTree.getRowCount(); i++) changesTree.expandRow(i);
        rightCardLayout.show(rightPanel, CHANGES_PREVIEW_CARD);
    }

    public void showJsonInputView() { rightCardLayout.show(rightPanel, JSON_INPUT_CARD); }
    public String getTheme() { return themeField.getText(); }

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
            historyDir.refresh(false, true);
            VirtualFile[] children = historyDir.getChildren();
            Arrays.sort(children, (f1, f2) -> f2.getName().compareTo(f1.getName()));
            DefaultListModel<VirtualFile> model = new DefaultListModel<>();
            for (VirtualFile child : children) {
                if (child.isDirectory()) model.addElement(child);
            }
            historyList.setModel(model);
        }
    }
}
