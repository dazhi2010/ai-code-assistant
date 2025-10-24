package cn.com.wewell.aicodeassistant.service;

import cn.com.wewell.aicodeassistant.model.AiResponseAction;
import cn.com.wewell.aicodeassistant.model.CodeSnippet;
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

@Service(Service.Level.PROJECT)
public final class PromptManager {

    private static final String PROMPT_TEMPLATE = """
            请你扮演一位资深的Java开发专家，严格按照我提供的上下文信息和格式要求，为我提供代码解决方案。

            # 上下文信息
            我当前的项目结构和代码片段如下：
            %s

            # 任务要求
            请根据我提供的"我的需求"，分析并给出需要对代码进行哪些修改。

            # 通用编码准则
            1.  **请勿进行不必要的修改**：例如，不要随意改动代码格式、不要增删不影响功能的空行、不要调整已有导入语句的顺序。只修改与需求直接相关的部分。
            2.  **注释规范**：所有你新增或修改的代码注释，请务必使用中文。

            # 输出格式要求
            你必须严格以JSON格式返回你的所有响应，不要有任何额外的解释、注释或引言。
            JSON的根节点是一个包含多个操作对象的数组。每个操作对象都必须包含 "action" 和 "filePath" 字段。

            **核心原则：请使用代码块本身进行定位，而不是行号。**

            支持的 "action" 类型包括：
            1.  `CREATE`: 创建一个新文件。需要 "content" 字段。
            2.  `OVERWRITE`: 覆盖整个文件。当变更非常复杂或涉及文件大部分内容时使用。需要 "content" 字段。
            3.  `UPDATE`: **（重要）** 更新文件中的一处代码。
                *   必须包含 `oldCodeBlock` 字段：代表需要被替换的、完整的、多行的原始代码块。
                *   必须包含 `newCodeBlock` 字段：代表替换后的新代码块。
            4.  `INSERT_AFTER`: **（重要）** 在指定代码块之后插入新代码。
                *   必须包含 `oldCodeBlock` 字段：作为锚点，新代码将插入在这一块代码之后。
                *   必须包含 `newCodeBlock` 字段：要插入的新代码。
            5.  `DELETE`: **（重要）** 删除文件中的一部分代码。
                *   必须包含 `oldCodeBlock` 字段：需要被删除的完整代码块。

            **注意**：`oldCodeBlock` 必须是原始文件中连续且唯一的代码片段，以确保能精确匹配。如果代码片段不唯一，请包含更多上下文行来确保唯一性。

            ## JSON格式示例
            ```json
            [
              {
                "action": "UPDATE",
                "filePath": "src/main/java/com/example/MyService.java",
                "oldCodeBlock": "    public int calculate() {\n        return 1 + 1; // 原始实现\n    }",
                "newCodeBlock": "    /**\n     * 计算总和，这是一个新的中文注释。\n     */\n    public int calculate() {\n        return 2 + 2; // 修正后的实现\n    }"
              },
              {
                "action": "INSERT_AFTER",
                "filePath": "src/main/java/com/example/MyService.java",
                "oldCodeBlock": "    public int calculate() {\n        return 2 + 2; // 修正后的实现\n    }",
                "newCodeBlock": "\n    public void anotherMethod() {\n        // 这是新增的方法\n    }"
              },
              {
                "action": "DELETE",
                "filePath": "src/main/java/com/example/OldUtil.java",
                "oldCodeBlock": "    @Deprecated\n    public static void oldFunction() {\n        // no-op\n    }"
              }
            ]
            ```

            请现在开始分析并提供JSON格式的解决方案。
            """;

    private final List<CodeSnippet> snippets = new ArrayList<>();
    private final StringBuilder inputContent = new StringBuilder();
    private String outputContent = "";
    private List<AiResponseAction> parsedActions = new ArrayList<>();

    private Consumer<String> inputListener;
    private Consumer<String> outputListener;

    public static PromptManager getInstance(Project project) {
        return project.getService(PromptManager.class);
    }

    public void addCodeSnippet(CodeSnippet snippet) {
        snippets.add(snippet);
        rebuildInputContent();
        notifyInputListener();
    }

    public void addText(String text) {
        inputContent.append(text);
        notifyInputListener();
    }

    public void syncInputContent(String content) {
        if (!inputContent.toString().equals(content)) {
            inputContent.setLength(0);
            inputContent.append(content);
            // 这里不需要重新解析 snippets，因为UI的修改被视为最终权威
        }
    }

    public void generateFullPrompt() {
        String finalPrompt = String.format(PROMPT_TEMPLATE, inputContent.toString());
        inputContent.setLength(0);
        inputContent.append(finalPrompt);
        notifyInputListener();
    }

    private void rebuildInputContent() {
        inputContent.setLength(0);
        for (int i = 0; i < snippets.size(); i++) {
            inputContent.append(snippets.get(i).toFormattedString(i + 1));
            if (i < snippets.size() - 1) {
                inputContent.append("\n\n");
            }
        }
        if (!snippets.isEmpty()) {
            inputContent.append("\n\n");
        }
        inputContent.append("## 我的需求\n[请在这里补充您的需求描述...]");
    }

    public String getInputContent() {
        return inputContent.toString();
    }

    public void setOutputContent(String content) {
        if (!this.outputContent.equals(content)) {
            this.outputContent = content;
        }
    }
    // 这个方法由 Action 调用，需要更新UI
    public void setOutputContentAndNotify(String content) {
        if (!this.outputContent.equals(content)) {
            this.outputContent = content;
            notifyOutputListener();
        }
    }

    // 这个方法由 UI 的 DocumentListener 调用，只同步数据，不通知UI
    public void syncOutputContent(String content) {
        if (!this.outputContent.equals(content)) {
            this.outputContent = content;
        }
    }

    public String getOutputContent() {
        return outputContent;
    }

    public List<AiResponseAction> getParsedActions() {
        return parsedActions;
    }

    public boolean parseResponse() {
        try {
            Gson gson = new Gson();
            Type listType = new TypeToken<List<AiResponseAction>>() {
            }.getType();
            parsedActions = gson.fromJson(outputContent, listType);
            if (parsedActions == null || parsedActions.stream().anyMatch(a -> a.action() == null || a.filePath() == null)) {
                parsedActions = null;
                return false;
            }
            return true;
        } catch (JsonSyntaxException e) {
            parsedActions = null;
            return false;
        }
    }

    public void clearAll() {
        snippets.clear();
        inputContent.setLength(0);
        outputContent = "";
        parsedActions.clear();
        notifyInputListener();
        notifyOutputListener();
    }

    public void setInputListener(Consumer<String> listener) {
        this.inputListener = listener;
    }

    public void setOutputListener(Consumer<String> listener) {
        this.outputListener = listener;
    }

    private void notifyInputListener() {
        if (inputListener != null) {
            ApplicationManager.getApplication().invokeLater(() -> inputListener.accept(inputContent.toString()));
        }
    }

    private void notifyOutputListener() {
        if (outputListener != null) {
            ApplicationManager.getApplication().invokeLater(() -> outputListener.accept(outputContent));
        }
    }
}