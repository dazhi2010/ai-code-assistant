package cn.com.wewell.aicodeassistant.model;

import java.util.List;

/**
 * 中文注释：.ai-assistant/config.json 的数据模型，当前仅支持 ignore 列表。
 */
public record AssistantConfig(List<String> ignore) {
}
