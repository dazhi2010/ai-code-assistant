package cn.com.wewell.aicodeassistant.action;

import cn.com.wewell.aicodeassistant.common.Constants;
import cn.com.wewell.aicodeassistant.model.CodeSnippet;
import cn.com.wewell.aicodeassistant.service.PromptManager;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.SelectionModel;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;

public class AddToAssistantAction extends AnAction {

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        PsiFile psiFile = e.getData(CommonDataKeys.PSI_FILE);

        if (project == null || editor == null || psiFile == null) {
            return;
        }

        SelectionModel selectionModel = editor.getSelectionModel();
        String selectedText = selectionModel.getSelectedText();
        if (selectedText == null || selectedText.isEmpty()) {
            return;
        }

        Document document = editor.getDocument();
        int startLine = document.getLineNumber(selectionModel.getSelectionStart()) + 1;
        int endLine = document.getLineNumber(selectionModel.getSelectionEnd()) + 1;

        VirtualFile virtualFile = psiFile.getVirtualFile();
        String relativePath = VfsUtilCore.getRelativePath(virtualFile, project.getBaseDir());

        String language = psiFile.getLanguage().getID();

        CodeSnippet snippet = new CodeSnippet(selectedText, relativePath, startLine, endLine, language);

        PromptManager.getInstance(project).addCodeSnippet(snippet);

        ToolWindow toolWindow = ToolWindowManager.getInstance(project).getToolWindow(Constants.TOOL_WINDOW_ID);
        if (toolWindow != null) {
            toolWindow.activate(null);
        }
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        e.getPresentation().setEnabledAndVisible(
                project != null && editor != null && editor.getSelectionModel().hasSelection()
        );
    }
}