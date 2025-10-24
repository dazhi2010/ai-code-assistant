package cn.com.wewell.aicodeassistant.model;

// 使用 Record 定义一个不可变的数据载体
public record AiResponseAction(
        String action,
        String filePath,
        Integer startLine,
        Integer endLine,
        Integer line,
        String content,
        String oldCodeBlock,
        String newCodeBlock
) {}