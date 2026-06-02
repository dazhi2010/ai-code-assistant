package cn.com.wewell.aicodeassistant.service;

import cn.com.wewell.aicodeassistant.common.Constants;
import cn.com.wewell.aicodeassistant.model.AssistantConfig;
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 中文注释：
 * 该服务负责管理 .ai-assistant/config.json 配置文件，提供以下能力：
 * 1) 确保默认配置文件存在；
 * 2) 读取忽略规则列表；
 * 3) 提供 isIgnored 判断，支持名称、目录、*、? 等基础通配符（简化版 glob）；
 * 4) 提供 reload() 手动刷新，以便配置修改后即时生效。
 */
@Service(Service.Level.PROJECT)
public final class AssistantConfigService {

    private static final String DEFAULT_PROMPT_EXPERT_ROLE = "JAVA专家";
    private static final String DEFAULT_AUTHOR_NAME = "yuqf";

    private final Project project;
    private final Gson gson = new Gson();
    private volatile AssistantConfig cachedConfig;

    public AssistantConfigService(Project project) {
        this.project = project;
    }

    public static AssistantConfigService getInstance(Project project) {
        return project.getService(AssistantConfigService.class);
    }

    /**
     * 中文注释：确保 .ai-assistant/config.json 存在，如不存在则创建并写入默认内容。
     */
    public void ensureDefaultConfig() {
        String basePath = project.getBasePath();
        if (basePath == null) return;

        ApplicationManager.getApplication().runWriteAction(() -> {
            try {
                String dirPath = basePath + "/" + Constants.ASSISTANT_DIR;
                VirtualFile dir = VfsUtil.createDirectories(dirPath);
                VirtualFile cfg = dir.findChild(Constants.CONFIG_FILE);
                if (cfg == null || cfg.getLength() == 0) {
                    AssistantConfig defaultCfg = defaultConfig();
                    byte[] bytes = gson.toJson(defaultCfg).getBytes(StandardCharsets.UTF_8);
                    if (cfg == null) {
                        cfg = dir.createChildData(this, Constants.CONFIG_FILE);
                    }
                    cfg.setBinaryContent(bytes);
                    cachedConfig = defaultCfg; // 缓存默认配置
                }
            } catch (IOException ignored) {
                // 中文注释：若创建失败，后续读取时走兜底逻辑
            }
        });
    }

    /**
     * 中文注释：手动刷新配置，使得外部修改可以立即生效。
     */
    public void reload() {
        cachedConfig = null;
        ensureDefaultConfig();
        loadConfig();
    }

    /**
     * 中文注释：返回忽略规则列表（从配置文件读取，读取失败则返回空列表或默认值）。
     */
    public List<String> getIgnorePatterns() {
        AssistantConfig cfg = loadConfig();
        if (cfg == null || cfg.ignore() == null) return Collections.emptyList();
        return cfg.ignore();
    }

    public String getPromptExpertRole() {
        AssistantConfig cfg = loadConfig();
        if (cfg == null || cfg.promptExpertRole() == null || cfg.promptExpertRole().isBlank()) {
            return DEFAULT_PROMPT_EXPERT_ROLE;
        }
        return cfg.promptExpertRole().trim();
    }

    public String getAuthorName() {
        AssistantConfig cfg = loadConfig();
        if (cfg == null || cfg.authorName() == null || cfg.authorName().isBlank()) {
            return DEFAULT_AUTHOR_NAME;
        }
        return cfg.authorName().trim();
    }

    /**
     * 中文注释：判断某个 VirtualFile 是否应被忽略。
     */
    public boolean isIgnored(VirtualFile file) {
        String relPath = getRelativePath(file);
        if (relPath == null) return false;
        // 统一用正斜杠
        relPath = relPath.replace('\\', '/');
        boolean isDir = file.isDirectory();
        if (isDir && !relPath.endsWith("/")) {
            relPath = relPath + "/";
        }

        for (String raw : getIgnorePatterns()) {
            if (raw == null) continue;
            String p = raw.trim();
            if (p.isEmpty() || p.startsWith("#")) continue; // 中文注释：跳过空行与注释
            if (matchesPattern(p, relPath, isDir)) return true;
        }
        return false;
    }

    // ========================= 内部实现 =========================

    private @Nullable String getRelativePath(VirtualFile file) {
        VirtualFile base = project.getBaseDir();
        if (base == null) return null;
        return VfsUtil.getRelativePath(file, base, '/');
    }

    private @Nullable AssistantConfig loadConfig() {
        if (cachedConfig != null) return cachedConfig;
        String basePath = project.getBasePath();
        if (basePath == null) return null;
        String cfgPath = basePath + "/" + Constants.CONFIG_PATH;
        VirtualFile cfgFile = LocalFileSystem.getInstance().findFileByPath(cfgPath);
        if (cfgFile == null) {
            ensureDefaultConfig();
            cfgFile = LocalFileSystem.getInstance().refreshAndFindFileByPath(cfgPath);
            if (cfgFile == null) return null;
        }
        try {
            String json = VfsUtilCore.loadText(cfgFile);
            AssistantConfig cfg = gson.fromJson(json, AssistantConfig.class);
            cachedConfig = cfg != null ? cfg : defaultConfig();
        } catch (IOException | JsonSyntaxException e) {
            cachedConfig = defaultConfig();
        }
        return cachedConfig;
    }

    private AssistantConfig defaultConfig() {
        return new AssistantConfig(defaultIgnores(), DEFAULT_PROMPT_EXPERT_ROLE, DEFAULT_AUTHOR_NAME);
    }

    /**
     * 中文注释：简化版匹配逻辑（支持：名称匹配、目录匹配、* 与 ?）。
     * 规则说明：
     * - 以 "/" 开头表示从项目根开始匹配；否则在任意层级匹配。
     * - 以 "/" 结尾表示目录模式，会匹配该目录及其全部子内容。
     * - 无斜杠的规则仅匹配文件或目录名本身（任意层级）。
     */
    private boolean matchesPattern(String rawPattern, String relPath, boolean isDir) {
        String pattern = rawPattern.replace('\\', '/');
        boolean anchored = pattern.startsWith("/");
        boolean dirPat = pattern.endsWith("/");
        if (anchored) pattern = pattern.substring(1);
        if (dirPat) pattern = pattern.substring(0, pattern.length() - 1);

        // 中文注释：将通配符转换为正则主体（不加 ^$）
        String body = globToRegexBody(pattern);
        String regex;

        // 中文注释：无斜杠的规则，仅匹配名称（最后一段）；有斜杠的规则匹配路径子串
        boolean hasSlash = pattern.contains("/");
        if (!hasSlash) {
            // 名称匹配：确保匹配在路径段边界处
            if (dirPat) {
                // 目录名：匹配该目录或子项
                regex = "(^|.*/)" + body + "(?:/.*)?$";
            } else {
                // 文件名：匹配该名称
                regex = "(^|.*/)" + body + "$";
            }
        } else {
            // 含路径的规则
            if (anchored) {
                regex = "^" + body + (dirPat ? "(?:/.*)?$" : "$");
            } else {
                regex = ".*" + body + (dirPat ? "(?:/.*)?$" : "$");
            }
        }

        Pattern p = Pattern.compile(regex);
        return p.matcher(relPath).matches();
    }

    /**
     * 中文注释：将简化 glob 转为正则主体：
     * *  -> [^/]*
     * ?  -> [^/]
     * 其他正则元字符进行转义
     */
    private String globToRegexBody(String glob) {
        StringBuilder sb = new StringBuilder(glob.length() * 2);
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            switch (c) {
                case '*':
                    sb.append("[^/]*");
                    break;
                case '?':
                    sb.append("[^/]");
                    break;
                case '.':
                case '(':
                case ')':
                case '+':
                case '|':
                case '^':
                case '$':
                case '@':
                case '%':
                case '{':
                case '}':
                case '[':
                case ']':
                case '\\':
                    sb.append('\\').append(c);
                    break;
                default:
                    sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * 中文注释：默认忽略规则（初始写入 config.json）。
     */
    private List<String> defaultIgnores() {
        List<String> list = new ArrayList<>();
        list.add(".git/");
        list.add(".idea/");
        list.add(".gradle/");
        list.add("build/");
        list.add("target/");
        list.add("out/");
        list.add("node_modules/");
        list.add("*.iml");
        list.add("*.class");
        list.add("*.jar");
        return list;
    }
}
