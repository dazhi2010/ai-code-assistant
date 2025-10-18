package cn.com.wewell.aicodeassistant.common;

public final class Constants {
    // 将您在 plugin.xml 中定义的 ID 放在这里
    public static final String TOOL_WINDOW_ID = "Yuqf AI Assistant";
    public static final String NOTIFICATION_GROUP_ID = "Yuqf AI Assistant Notifications";

    // 中文注释：AI 助手的工作目录及配置文件
    public static final String ASSISTANT_DIR = ".ai-assistant";
    public static final String CONFIG_FILE = "config.json";
    public static final String CONFIG_PATH = ASSISTANT_DIR + "/" + CONFIG_FILE;

    private Constants() {
        // 私有构造函数，防止实例化
    }
}
