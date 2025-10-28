package cn.com.wewell.aicodeassistant.service;

import cn.com.wewell.aicodeassistant.model.AiResponseAction;
import cn.com.wewell.aicodeassistant.model.CodeSnippet;
import com.google.gson.*;
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
             1.  请勿进行不必要的修改（格式、空行、导入顺序等）。只修改与需求直接相关的部分。
             2.  注释规范：所有你新增或修改的代码注释，请务必使用中文。
            
             # 输出格式要求
             你必须严格以JSON格式返回响应，不要有任何额外的解释或注释。
             优先返回一个对象，包含：
             - actions: 操作数组（与之前规范一致）
             - requires: 若上下文不足，请在此列出需要补充的文件或类
             若无需补充，也可返回空数组；同时兼容直接返回 `actions` 的数组（向后兼容）。
            
             核心原则：使用代码块本身进行定位，而不是行号。
            
             支持的 action 类型：
             1.  CREATE: 创建新文件，需提供 content。
             2.  OVERWRITE: 覆盖整个文件，需提供 content。
             3.  UPDATE: 用 newCodeBlock 替换 oldCodeBlock（两者必须存在）。
             4.  INSERT_AFTER: 在 oldCodeBlock 之后插入 newCodeBlock（两者必须存在）。
             5.  DELETE: 删除 oldCodeBlock（必须存在）。
            
             requires 结构：
             {
               "files": ["src/main/java/com/example/A.java", "pom.xml"],
               "classes": ["com.example.service.UserService", "MyUtil"]
             }
            
             示例：
             ```json
             {
               "actions": [
                 {
                   "action": "UPDATE",
                   "filePath": "src/main/java/com/example/MyService.java",
                   "oldCodeBlock": "    public int calculate() {\\\\n        return 1 + 1; // 原始实现\\\\n    }",
                   "newCodeBlock": "    /**\\\\n     * 计算总和，这是一个新的中文注释。\\\\n     */\\\\n    public int calculate() {\\\\n        return 2 + 2; // 修正后的实现\\\\n    }"
                 }
               ],
               "requires": {
                 "files": ["src/main/java/com/example/Dependent.java"],
                 "classes": ["com.example.external.ExternalApi"]
               }
             }
            ```

            请现在开始分析并提供JSON格式的解决方案。
            """;

    private final List<CodeSnippet> snippets = new ArrayList<>();
    private final StringBuilder inputContent = new StringBuilder();
    private String outputContent = "";
    private List<AiResponseAction> parsedActions = new ArrayList<>();
    private final List<String> requiredFiles = new ArrayList<>();
    private final List<String> requiredClasses = new ArrayList<>();

    public List<String> getRequiredFiles() { return requiredFiles; }
    public List<String> getRequiredClasses() { return requiredClasses; }

    private Consumer<String> inputListener;
    private Consumer<String> outputListener;

    public static PromptManager getInstance(Project project) {
        return project.getService(PromptManager.class);
    }
    // 新增：输入区替换并通知
    public void setInputContentAndNotify(String text) {
        inputContent.setLength(0);
        inputContent.append(text != null ? text : "");
        notifyInputListener();
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
        String appendix = """
                # 生成规则强化（重要）
                1. 优先使用 UPDATE 表达小范围修改；仅当需要大规模替换整文件时使用 OVERWRITE；仅插入使用 INSERT_AFTER；删除使用 DELETE。
                2. oldCodeBlock 必须是原文件中唯一且可直接匹配的完整片段：请在目标代码前后各附加若干行上下文，以保证唯一性（必要时包含方法签名/类签名/标签与选择器等）。
                3. 对于我提供的“紧凑模式”片段（例如 Java 方法体被替换为 { /* body omitted */ }、JS/CSS 规则被省略等），不要对这些省略区域进行修改；若需修改，请在 requires 中列出需要的完整文件或类（使用全限定名）。
                4. 当定位困难时，扩大 oldCodeBlock 的上下文范围，确保在原文件中仅出现一次；若仍不唯一，请在 requires 中请求更多上下文。
                5. 当 requires 非空时，只返回 requires，不要在同一响应中返回 actions；等待我补齐所需文件/类后再继续。
                6. 若需要很多文件或类，请一次性完整列出清单，不要分批请求。
                7. files 与 classes 不要重复；若两者都能表达同一实体，优先使用 files。
                """;
        inputContent.setLength(0);
        inputContent.append(finalPrompt).append("\n\n").append(appendix);
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
        requiredFiles.clear();
        requiredClasses.clear();
        parsedActions = new ArrayList<>();

        try {
            JsonElement root = JsonParser.parseString(outputContent);
            Gson gson = new Gson();

            if (root.isJsonArray()) {
                Type listType = new TypeToken<List<AiResponseAction>>(){}.getType();
                parsedActions = gson.fromJson(root, listType);
            } else if (root.isJsonObject()) {
                JsonObject obj = root.getAsJsonObject();
                // actions 或 changes
                String actionsKey = obj.has("actions") ? "actions" : (obj.has("changes") ? "changes" : null);
                if (actionsKey != null) {
                    Type listType = new TypeToken<List<AiResponseAction>>(){}.getType();
                    parsedActions = gson.fromJson(obj.get(actionsKey), listType);
                } else {
                    parsedActions = new ArrayList<>();
                }
                // requires / needs
                JsonObject requires = null;
                if (obj.has("requires") && obj.get("requires").isJsonObject()) {
                    requires = obj.getAsJsonObject("requires");
                } else if (obj.has("needs") && obj.get("needs").isJsonObject()) {
                    requires = obj.getAsJsonObject("needs");
                }
                if (requires != null) {
                    if (requires.has("files") && requires.get("files").isJsonArray()) {
                        for (JsonElement el : requires.getAsJsonArray("files")) {
                            if (el.isJsonPrimitive()) requiredFiles.add(el.getAsString());
                        }
                    } else if (requires.has("needFiles") && requires.get("needFiles").isJsonArray()) {
                        for (JsonElement el : requires.getAsJsonArray("needFiles")) {
                            if (el.isJsonPrimitive()) requiredFiles.add(el.getAsString());
                        }
                    }
                    if (requires.has("classes") && requires.get("classes").isJsonArray()) {
                        for (JsonElement el : requires.getAsJsonArray("classes")) {
                            if (el.isJsonPrimitive()) requiredClasses.add(el.getAsString());
                        }
                    } else if (requires.has("needClasses") && requires.get("needClasses").isJsonArray()) {
                        for (JsonElement el : requires.getAsJsonArray("needClasses")) {
                            if (el.isJsonPrimitive()) requiredClasses.add(el.getAsString());
                        }
                    }
                }                // 规范化与去重：文件与类不要重复（文件优先）
                {
                    java.util.LinkedHashSet<String> files = new java.util.LinkedHashSet<>();
                    for (String f : new java.util.ArrayList<>(requiredFiles)) {
                        if (f != null) {
                            String norm = f.trim().replace('\\', '/');
                            if (!norm.isBlank()) files.add(norm);
                        }
                    }
                    java.util.LinkedHashSet<String> classes = new java.util.LinkedHashSet<>();
                    for (String c : new java.util.ArrayList<>(requiredClasses)) {
                        if (c != null) {
                            String norm = c.trim();
                            if (!norm.isBlank()) classes.add(norm);
                        }
                    }
                    // 基于文件名的跨类型去重（优先保留文件）
                    java.util.Set<String> fileBaseNames = new java.util.HashSet<>();
                    for (String p : files) {
                        String path = p;
                        int slash = path.lastIndexOf('/');
                        String name = slash >= 0 ? path.substring(slash + 1) : path;
                        int dot = name.lastIndexOf('.');
                        String base = dot >= 0 ? name.substring(0, dot) : name;
                        fileBaseNames.add(base.toLowerCase(java.util.Locale.ROOT));
                    }
                    classes.removeIf(fqn -> {
                        int dot = fqn.lastIndexOf('.');
                        String simple = dot >= 0 ? fqn.substring(dot + 1) : fqn;
                        return fileBaseNames.contains(simple.toLowerCase(java.util.Locale.ROOT));
                    });
                    requiredFiles.clear();
                    requiredFiles.addAll(files);
                    requiredClasses.clear();
                    requiredClasses.addAll(classes);
                }

                // 当 requires 非空时，遵循约定：只请求，不给出变更（等待用户补齐上下文）
                if (!requiredFiles.isEmpty() || !requiredClasses.isEmpty()) {
                    parsedActions = new java.util.ArrayList<>();
                }
            } else {
                parsedActions = null;
                return false;
            }

            // 校验 actions 基本合法性（可为空），但若有 actions 则需字段完整
            if (parsedActions != null && !parsedActions.isEmpty()) {
                boolean invalid = parsedActions.stream().anyMatch(a -> a.action() == null || a.filePath() == null);
                if (invalid) {
                    parsedActions = null;
                    return false;
                }
            }

            // 成功条件：actions 合法(可空) 或 requires 非空
            boolean ok = (parsedActions != null) && (!parsedActions.isEmpty() || !requiredFiles.isEmpty() || !requiredClasses.isEmpty());
            if (!ok) {
                // 允许空 actions，但 requires 也为空时认为失败
                return false;
            }
            return true;

        } catch (JsonSyntaxException e) {
            parsedActions = null;
            requiredFiles.clear();
            requiredClasses.clear();
            return false;
        }
    }

    public void clearAll() {
        snippets.clear();
        inputContent.setLength(0);
        outputContent = "";
        parsedActions.clear();
        requiredFiles.clear();
        requiredClasses.clear();
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