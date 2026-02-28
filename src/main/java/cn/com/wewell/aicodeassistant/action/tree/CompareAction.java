package cn.com.wewell.aicodeassistant.action.tree;

import cn.com.wewell.aicodeassistant.model.AiResponseAction;
import cn.com.wewell.aicodeassistant.service.PromptManager;
import com.intellij.diff.DiffContentFactory;
import com.intellij.diff.DiffManager;
import com.intellij.diff.contents.DocumentContent;
import com.intellij.diff.requests.SimpleDiffRequest;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.event.DocumentListener;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.fileTypes.FileTypeManager;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

import javax.swing.tree.DefaultMutableTreeNode;

/**
 * 弹出独立窗口对比新旧内容，支持实时编辑同步
 * @author yuqf
 */
public class CompareAction extends AnAction {
    private final DefaultMutableTreeNode node;

    public CompareAction(DefaultMutableTreeNode node) {
        super("新旧对比");
        this.node = node;
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null || !(node.getUserObject() instanceof AiResponseAction action)) return;

        String oldText = action.oldCodeBlock() != null ? action.oldCodeBlock() : "";
        String newText = action.newCodeBlock() != null ? action.newCodeBlock() : (action.content() != null ? action.content() : "");
        
        FileType fileType = FileTypeManager.getInstance().getFileTypeByFileName(action.filePath());

        // 核心修复：使用 EditorFactory 创建纯内存 Document，确保其可编辑性
        Document docOld = EditorFactory.getInstance().createDocument(oldText);
        Document docNew = EditorFactory.getInstance().createDocument(newText);
        
        docOld.setReadOnly(false);
        docNew.setReadOnly(false);

        DocumentContent oldContent = DiffContentFactory.getInstance().create(project, docOld, fileType);
        DocumentContent newContent = DiffContentFactory.getInstance().create(project, docNew, fileType);

        // 添加同步监听器
        docOld.addDocumentListener(new DocumentListener() {
            @Override
            public void documentChanged(@NotNull com.intellij.openapi.editor.event.DocumentEvent event) {
                PromptManager.getInstance(project).updateActionContent(action, event.getDocument().getText(), docNew.getText());
            }
        });

        docNew.addDocumentListener(new DocumentListener() {
            @Override
            public void documentChanged(@NotNull com.intellij.openapi.editor.event.DocumentEvent event) {
                PromptManager.getInstance(project).updateActionContent(action, docOld.getText(), event.getDocument().getText());
            }
        });

        SimpleDiffRequest request = new SimpleDiffRequest("代码比对 (编辑将实时生效): " + action.filePath(), oldContent, newContent, "目标内容 (旧)", "新内容 (新)");
        
        // 使用非模态方式打开对比窗口
        DiffManager.getInstance().showDiff(project, request);
    }
}
