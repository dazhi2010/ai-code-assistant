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

/**
 * 管理提示词生成与响应解析的核心服务
 * @author yuqf
 */
@Service(Service.Level.PROJECT)
public final class PromptManager {

    private static final String PROMPT_TEMPLATE = """
            # ROLE
            你是一位资深的 Java 专家。
            你的目标是基于用户提供的上下文，提供安全、优雅且符合工程规范的代码方案。

            # CONSTRAINTS & STANDARDS
            1. **最小影响原则**：严禁进行无关的格式化、重构或空行调整。只修改与需求直接相关的代码。
            2. **安全准则**：严禁硬编码 API 密钥、密钥对或绝对本地路径。
            3. **代码风格**：注释必须使用中文，新创建的文件需标注 `@author yuqf`。
            4. **唯一性定位**：在 `UPDATE` 操作中，`oldCodeBlock` 必须在目标文件中唯一存在。如有必要，请在前后包含若干行上下文（包括方法签名、特有的逻辑行等）以确保定位精准。

            # RESPONSE PROTOCOL (JSON REQUIRED)
            你必须返回一个符合以下结构的 JSON 对象。严禁包含任何 Markdown 格式块之外的自然语言解释。
            {
              "thought": "对需求的深入分析、架构选择及实现思路的描述",
              "feedback": "如果你有非代码类的指导（如：安装特定插件、配置环境变量、修改 IDE 设置）或发现上下文不足需要补充，请在此说明",
              "commands": ["可选：需要用户在 IDE 终端执行的安装、构建或运行命令"],
              "actions": [
                {
                  "action": "CREATE | OVERWRITE | UPDATE | INSERT_AFTER | DELETE",
                  "filePath": "相对于项目根目录的路径",
                  "oldCodeBlock": "用于定位的原始代码片段 (UPDATE/INSERT_AFTER/DELETE 必须提供)",
                  "newCodeBlock": "修改后的代码片段 (CREATE/OVERWRITE/UPDATE/INSERT_AFTER 必须提供)",
                  "content": "完整文件内容 (仅在 CREATE/OVERWRITE 且不提供 newCodeBlock 时使用)",
                  "explanation": "简述该项变更的具体原因"
                }
              ],
              "requires": {
                "files": ["若需更多文件内容，列出路径"],
                "classes": ["若需更多类信息，列出全限定名"]
              }
            }

            # NOTE
            - 优先使用 `UPDATE` 进行局部修改；仅在涉及大范围结构调整或新文件时使用 `CREATE/OVERWRITE`。
            - **严禁重复**：在使用 `INSERT_AFTER` 时，`newCodeBlock` 应该是纯粹要插入的新内容，**绝对不能**包含 `oldCodeBlock` 中的任何内容，否则会导致代码重复。
            - 如果你认为现有的上下文信息不足以给出完整且正确的方案，请优先在 `requires` 中请求必要信息，并在 `feedback` 中说明原因。

            # CONTEXT & REQUIREMENT
            以下是我当前的项目结构、涉及的代码片段及我的核心需求：
            
            %s
            """;

    private final List<CodeSnippet> snippets = new ArrayList<>();
    private final StringBuilder inputContent = new StringBuilder();
    private String outputContent = "";
    private List<AiResponseAction> parsedActions = new ArrayList<>();
    private final List<String> requiredFiles = new ArrayList<>();
    private final List<String> requiredClasses = new ArrayList<>();
    
    // 新增回复解析字段
    private String aiThought = "";
    private String aiFeedback = "";
    private final List<String> aiCommands = new ArrayList<>();

    private Consumer<String> inputListener;
    private Consumer<String> outputListener;

    public static PromptManager getInstance(Project project) {
        return project.getService(PromptManager.class);
    }

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
        }
    }

    public void generateFullPrompt() {
        String dataContent = inputContent.toString();
        String finalPrompt = String.format(PROMPT_TEMPLATE, dataContent);
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
        this.outputContent = content;
    }

    public void setOutputContentAndNotify(String content) {
        if (!this.outputContent.equals(content)) {
            this.outputContent = content;
            notifyOutputListener();
        }
    }

    public void syncOutputContent(String content) {
        this.outputContent = content;
    }

    public String getOutputContent() {
        return outputContent;
    }

    public List<AiResponseAction> getParsedActions() {
        return parsedActions;
    }

    public String getAiThought() { return aiThought; }
    public String getAiFeedback() { return aiFeedback; }
    public List<String> getAiCommands() { return aiCommands; }
    public List<String> getRequiredFiles() { return requiredFiles; }
    public List<String> getRequiredClasses() { return requiredClasses; }

    public boolean parseResponse() {
        parsedActions = new ArrayList<>();
        requiredFiles.clear();
        requiredClasses.clear();
        aiThought = "";
        aiFeedback = "";
        aiCommands.clear();

        if (outputContent == null || outputContent.isBlank()) return false;

        try {
            // 剥离 Markdown 块标识
            String jsonText = outputContent.trim();
            if (jsonText.startsWith("```")) {
                int firstLineEnd = jsonText.indexOf('\n');
                int lastBackticks = jsonText.lastIndexOf("```");
                if (firstLineEnd != -1 && lastBackticks > firstLineEnd) {
                    jsonText = jsonText.substring(firstLineEnd, lastBackticks).trim();
                }
            }

            JsonElement root = JsonParser.parseString(jsonText);
            Gson gson = new Gson();

            if (root.isJsonArray()) {
                // 向后兼容：直接返回 Action 数组
                Type listType = new TypeToken<List<AiResponseAction>>(){}.getType();
                parsedActions = gson.fromJson(root, listType);
            } else if (root.isJsonObject()) {
                JsonObject obj = root.getAsJsonObject();

                // 提取扩展字段
                if (obj.has("thought")) aiThought = obj.get("thought").getAsString();
                if (obj.has("feedback")) aiFeedback = obj.get("feedback").getAsString();
                if (obj.has("commands") && obj.get("commands").isJsonArray()) {
                    for (JsonElement el : obj.get("commands").getAsJsonArray()) {
                        aiCommands.add(el.getAsString());
                    }
                }

                // 提取 actions
                String actionsKey = obj.has("actions") ? "actions" : (obj.has("changes") ? "changes" : null);
                if (actionsKey != null && obj.get(actionsKey).isJsonArray()) {
                    Type listType = new TypeToken<List<AiResponseAction>>(){}.getType();
                    parsedActions = gson.fromJson(obj.get(actionsKey), listType);
                    
                    // 填充 CREATE/OVERWRITE 的 content 字段
                    if (parsedActions != null) {
                        for (AiResponseAction action : parsedActions) {
                            if (("CREATE".equalsIgnoreCase(action.action()) || "OVERWRITE".equalsIgnoreCase(action.action()))
                                    && (action.content() == null || action.content().isBlank())
                                    && action.newCodeBlock() != null) {
                                action.setContent(action.newCodeBlock());
                            }
                        }
                    }
                }

                // 提取 requires
                JsonObject reqObj = null;
                if (obj.has("requires") && obj.get("requires").isJsonObject()) {
                    reqObj = obj.getAsJsonObject("requires");
                }
                if (reqObj != null) {
                    if (reqObj.has("files") && reqObj.get("files").isJsonArray()) {
                        for (JsonElement el : reqObj.get("files").getAsJsonArray()) {
                            requiredFiles.add(el.getAsString());
                        }
                    }
                    if (reqObj.has("classes") && reqObj.get("classes").isJsonArray()) {
                        for (JsonElement el : reqObj.get("classes").getAsJsonArray()) {
                            requiredClasses.add(el.getAsString());
                        }
                    }
                }
                
                normalizeRequires();
                
                // 约定：若有请求信息且无行动，则不视为解析失败
                if ((!requiredFiles.isEmpty() || !requiredClasses.isEmpty()) && (parsedActions == null || parsedActions.isEmpty())) {
                    return true;
                }
            } else {
                return false;
            }

            // 基础校验
            if (parsedActions != null && !parsedActions.isEmpty()) {
                parsedActions.removeIf(a -> a.action() == null || a.filePath() == null);
            }

            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 更新 Action 的内容（当用户在 Diff 窗口手动编辑后调用）
     * 此时内存中的 parsedActions 里的同一个对象会被修改，并同步回 UI 的 JSON 文本
     */
    public void updateActionContent(AiResponseAction action, String newOldCode, String newNewCode) {
        if (action == null) return;

        action.setOldCodeBlock(newOldCode);
        action.setNewCodeBlock(newNewCode);
        
        // 如果是 CREATE/OVERWRITE 类型，也更新其 content 字段
        if ("CREATE".equalsIgnoreCase(action.action()) || "OVERWRITE".equalsIgnoreCase(action.action())) {
            action.setContent(newNewCode);
        }

        reSerializeAndNotify();
    }

    /**
     * 将当前内存中的所有状态（Actions, Thought, Feedback, Requires等）重新序列化为 JSON 并通知 UI
     */
    public void reSerializeAndNotify() {
        JsonObject root = new JsonObject();
        root.addProperty("thought", aiThought);
        root.addProperty("feedback", aiFeedback);
        
        JsonArray cmdArray = new JsonArray();
        for (String cmd : aiCommands) cmdArray.add(cmd);
        root.add("commands", cmdArray);

        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        root.add("actions", gson.toJsonTree(parsedActions));

        JsonObject reqObj = new JsonObject();
        JsonArray filesArr = new JsonArray();
        for (String f : requiredFiles) filesArr.add(f);
        reqObj.add("files", filesArr);
        JsonArray classArr = new JsonArray();
        for (String c : requiredClasses) classArr.add(c);
        reqObj.add("classes", classArr);
        root.add("requires", reqObj);

        String newJson = "```json\n" + gson.toJson(root) + "\n```";
        setOutputContentAndNotify(newJson);
    }

    private void normalizeRequires() {
        java.util.LinkedHashSet<String> filesSet = new java.util.LinkedHashSet<>();
        for (String f : requiredFiles) {
            if (f != null && !f.isBlank()) filesSet.add(f.trim().replace('\\', '/'));
        }
        requiredFiles.clear();
        requiredFiles.addAll(filesSet);

        java.util.LinkedHashSet<String> classesSet = new java.util.LinkedHashSet<>();
        for (String c : requiredClasses) {
            if (c != null && !c.isBlank()) classesSet.add(c.trim());
        }
        requiredClasses.clear();
        requiredClasses.addAll(classesSet);
    }

    public void clearAll() {
        snippets.clear();
        inputContent.setLength(0);
        outputContent = "";
        parsedActions.clear();
        requiredFiles.clear();
        requiredClasses.clear();
        aiThought = "";
        aiFeedback = "";
        aiCommands.clear();
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
