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
            
            支持的 "action" 类型包括：
            1.  `CREATE`: 创建一个新文件。需要包含 "content" 字段。
            2.  `UPDATE`: 更新现有文件中的一部分。需要包含 "startLine", "endLine", "content" 字段。
            3.  `INSERT`: 在指定行之前插入代码。需要包含 "line", "content" 字段。
            4.  `DELETE`: 删除文件或文件中的一部分。如果删除行，需要 "startLine", "endLine" 字段；如果删除整个文件，则不需要额外字段。
            5.  `OVERWRITE`: **（新增）** 覆盖整个文件。当变更非常复杂或涉及文件大部分内容时，建议使用此操作。需要包含 "content" 字段，其内容为文件的最终完整代码。
            
            ## JSON格式示例
            ```json
            [
              {
                "action": "UPDATE",
                "filePath": "src/main/java/com/example/MyService.java",
                "startLine": 25,
                "endLine": 30,
                "content": "// 这是一个新的中文注释\\n新的代码内容..."
              },
              {
                "action": "OVERWRITE",
                "filePath": "src/main/java/com/example/ComplexClass.java",
                "content": "package com.example;\\n\\n/**\\n * 这是一个完全重构后的类，并使用了中文文档注释。\\n */\\npublic class ComplexClass {\\n  // ... 文件的所有最终内容\\n}"
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