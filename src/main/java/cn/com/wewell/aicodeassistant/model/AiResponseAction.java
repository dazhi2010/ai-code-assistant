package cn.com.wewell.aicodeassistant.model;

import com.google.gson.annotations.SerializedName;

/**
 * AI 针对单个文件的操作指令 (改为普通类以支持实时编辑同步)
 * @author yuqf
 */
public class AiResponseAction {
    private String action;
    private String filePath;
    private Integer startLine;
    private Integer endLine;
    private Integer line;
    private String content;
    private String oldCodeBlock;
    private String newCodeBlock;
    private String explanation;

    // Getter and Setter
    public String action() { return action; }
    public void setAction(String action) { this.action = action; }

    public String filePath() { return filePath; }
    public void setFilePath(String filePath) { this.filePath = filePath; }

    public Integer startLine() { return startLine; }
    public void setStartLine(Integer startLine) { this.startLine = startLine; }

    public Integer endLine() { return endLine; }
    public void setEndLine(Integer endLine) { this.endLine = endLine; }

    public Integer line() { return line; }
    public void setLine(Integer line) { this.line = line; }

    public String content() { return content; }
    public void setContent(String content) { this.content = content; }

    public String oldCodeBlock() { return oldCodeBlock; }
    public void setOldCodeBlock(String oldCodeBlock) { this.oldCodeBlock = oldCodeBlock; }

    public String newCodeBlock() { return newCodeBlock; }
    public void setNewCodeBlock(String newCodeBlock) { this.newCodeBlock = newCodeBlock; }

    public String explanation() { return explanation; }
    public void setExplanation(String explanation) { this.explanation = explanation; }
    
    // 兼容之前的 record 方法调用风格
    public String getAction() { return action; }
    public String getFilePath() { return filePath; }
    public String getOldCodeBlock() { return oldCodeBlock; }
    public String getNewCodeBlock() { return newCodeBlock; }
    public String getContent() { return content; }
}
