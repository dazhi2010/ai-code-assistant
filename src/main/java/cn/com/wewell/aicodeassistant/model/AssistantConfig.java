package cn.com.wewell.aicodeassistant.model;

import java.util.List;

/**
 * 中文注释：.ai-assistant/config.json 的数据模型。
 */
public record AssistantConfig(
        List<String> ignore,
        String promptExpertRole,
        String authorName
) {
}
