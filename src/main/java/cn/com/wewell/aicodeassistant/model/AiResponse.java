package cn.com.wewell.aicodeassistant.model;

import java.util.List;

/**
 * AI 完整响应结构体
 * @author yuqf
 */
public record AiResponse(
        String thought,
        String feedback,
        List<String> commands,
        List<AiResponseAction> actions,
        Requires requires
) {
    public record Requires(
            List<String> files,
            List<String> classes
    ) {}
}
