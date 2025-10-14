package cn.com.wewell.aicodeassistant.model;

public record CodeSnippet(
        String content,
        String filePath,
        int startLine,
        int endLine,
        String language
) {
    public String toFormattedString(int index) {
        return String.format(
                "## 代码片段 #%d\n文件路径: %s\n代码位置: 第%d-%d行\n```%s\n%s\n```",
                index,
                filePath,
                startLine,
                endLine,
                language.toLowerCase(),
                content
        );
    }
}