package com.example;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 代码块匹配与替换工具测试类
 * 支持忽略空白字符差异的模糊匹配
 *
 * @author yuqf
 */
public class CodeBlockMatcherTest {

    public static void main(String[] args) {
        // 步骤1: 定义 JSON 输入
        String jsonInput = """
                {
                  "oldCodeBlock": "          <el-col :span=\\"12\\">\\n            <el-form-item label=\\"数据类型\\" prop=\\"dataType\\">\\n              <el-select v-model=\\"form.dataType\\" placeholder=\\"请选择\\" style=\\"width: 100%\\">\\n                <el-option label=\\"处置记录\\" value=\\"处置记录\\" />\\n                <el-option label=\\"维护记录\\" value=\\"维护记录\\" />\\n                <el-option label=\\"实时监测数据\\" value=\\"实时监测数据\\" />\\n              </el-select>\\n            </el-form-item>\\n          </el-col>\\n          <el-col :span=\\"12\\">\\n            <el-form-item label=\\"接入方式\\" prop=\\"accessMethod\\">\\n              <el-select v-model=\\"form.accessMethod\\" placeholder=\\"请选择\\" style=\\"width: 100%\\">\\n                <el-option label=\\"HTTP/HTTPS\\" value=\\"HTTP/HTTPS\\" />\\n                <el-option label=\\"MQTT\\" value=\\"MQTT\\" />\\n                <el-option label=\\"数据库直连\\" value=\\"数据库直连\\" />\\n              </el-select>\\n            </el-form-item>\\n          </el-col>",
                  "newCodeBlock": "          <el-col :span=\\"12\\">\\n            <el-form-item label=\\"数据类型\\" prop=\\"dataType\\">\\n              <el-select v-model=\\"form.dataType\\" placeholder=\\"请选择\\" style=\\"width: 100%\\">\\n                <el-option v-for=\\"item in dictOptions.dataTypes\\" :key=\\"item\\" :label=\\"item\\" :value=\\"item\\" />\\n              </el-select>\\n            </el-form-item>\\n          </el-col>\\n          <el-col :span=\\"12\\">\\n            <el-form-item label=\\"接入方式\\" prop=\\"accessMethod\\">\\n              <el-select v-model=\\"form.accessMethod\\" placeholder=\\"请选择\\" style=\\"width: 100%\\">\\n                <el-option v-for=\\"item in dictOptions.accessMethods\\" :key=\\"item\\" :label=\\"item\\" :value=\\"item\\" />\\n              </el-select>\\n            </el-form-item>\\n          </el-col>"
                }
                """;

        // 步骤2: 定义目标代码
        String targetCode = """
                <template>
                
                
                        <el-row :gutter="20">
                          <el-col :span="12">
                            <el-form-item label="数据类型" prop="dataType">
                              <el-select v-model="form.dataType" placeholder="请选择" style="width: 100%">
                                <el-option label="处置记录" value="处置记录"/>
                                <el-option label="维护记录" value="维护记录"/>
                                <el-option label="实时监测数据" value="实时监测数据"/>
                              </el-select>
                            </el-form-item>
                          </el-col>
                          <el-col :span="12">
                            <el-form-item label="接入方式" prop="accessMethod">
                              <el-select v-model="form.accessMethod" placeholder="请选择" style="width: 100%">
                                <el-option label="HTTP/HTTPS" value="HTTP/HTTPS"/>
                                <el-option label="MQTT" value="MQTT"/>
                                <el-option label="数据库直连" value="数据库直连"/>
                              </el-select>
                            </el-form-item>
                          </el-col>
                        </el-row>
                
                        <el-row :gutter="20">
                          <el-col :span="12">
                            <el-form-item label="传输协议" prop="protocol">
                              <el-input v-model="form.protocol" placeholder="如: RESTful API"/>
                            </el-form-item>
                          </el-col>
                          <el-col :span="12">
                            <el-form-item label="数据格式" prop="format">
                              <el-input v-model="form.format" placeholder="如: JSON"/>
                            </el-form-item>
                          </el-col>
                        </el-row>
                
                </template>
                """;

        try {
            // 执行匹配和替换
            CodeBlockMatcher matcher = new CodeBlockMatcher();
            String result = matcher.matchAndReplace(jsonInput, targetCode);

            System.out.println("\n" + "=".repeat(80));
            System.out.println("【最终替换结果】");
            System.out.println("=".repeat(80));
            System.out.println(result);

        } catch (Exception e) {
            System.err.println("处理失败: " + e.getMessage());
            e.printStackTrace();
        }
    }
}

/**
 * 代码块匹配器
 * 实现忽略空白字符差异的模糊匹配和替换
 *
 * @author yuqf
 */
class CodeBlockMatcher {

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 解析 JSON 并执行匹配替换
     */
    public String matchAndReplace(String jsonInput, String targetCode) throws Exception {
        System.out.println("=".repeat(80));
        System.out.println("【步骤1: 解析 JSON】");
        System.out.println("=".repeat(80));

        JsonNode root = objectMapper.readTree(jsonInput);
        String oldCodeBlock = root.get("oldCodeBlock").asText();
        String newCodeBlock = root.get("newCodeBlock").asText();

        System.out.println("✓ JSON 解析成功");
        System.out.println("\n--- oldCodeBlock (前100字符) ---");
        System.out.println(oldCodeBlock.substring(0, Math.min(100, oldCodeBlock.length())) + "...");
        System.out.println("\n--- newCodeBlock (前100字符) ---");
        System.out.println(newCodeBlock.substring(0, Math.min(100, newCodeBlock.length())) + "...");

        System.out.println("\n" + "=".repeat(80));
        System.out.println("【步骤2: 标准化处理】");
        System.out.println("=".repeat(80));

        // 标准化 oldCodeBlock 用于匹配
        String normalizedOld = normalizeForMatching(oldCodeBlock);
        System.out.println("✓ oldCodeBlock 标准化完成");
        System.out.println("  原始长度: " + oldCodeBlock.length() + " -> 标准化后: " + normalizedOld.length());

        System.out.println("\n" + "=".repeat(80));
        System.out.println("【步骤3: 在目标代码中查找匹配】");
        System.out.println("=".repeat(80));

        // 构建模糊匹配的正则表达式
        String regexPattern = buildFlexibleRegex(oldCodeBlock);
        System.out.println("✓ 已构建灵活匹配正则表达式");

        Pattern pattern = Pattern.compile(regexPattern, Pattern.DOTALL);
        Matcher matcher = pattern.matcher(targetCode);

        if (matcher.find()) {
            String matchedContent = matcher.group();
            int startPos = matcher.start();
            int endPos = matcher.end();

            System.out.println("✓ 匹配成功!");
            System.out.println("  匹配位置: 字符 " + startPos + " 到 " + endPos);
            System.out.println("  匹配长度: " + matchedContent.length() + " 字符");
            System.out.println("\n--- 匹配到的内容 ---");
            System.out.println(matchedContent);

            System.out.println("\n" + "=".repeat(80));
            System.out.println("【步骤4: 执行替换】");
            System.out.println("=".repeat(80));

            // 保持原始缩进
            String indent = detectIndent(matchedContent);
            String adjustedNewCode = adjustIndent(newCodeBlock, indent);

            System.out.println("✓ 检测到缩进: '" + indent.replace(" ", "·") + "' (" + indent.length() + " 空格)");

            // 保留匹配内容开头和结尾的空白字符（换行符等）
            String leadingWhitespace = "";
            String trailingWhitespace = "";

            // 提取开头的空白字符（换行符、空格等）
            for (int i = 0; i < matchedContent.length(); i++) {
                char c = matchedContent.charAt(i);
                if (c == '\n' || c == '\r') {
                    leadingWhitespace += c;
                } else if (Character.isWhitespace(c) && leadingWhitespace.contains("\n")) {
                    // 换行后的空格属于缩进，不算前导空白
                    break;
                } else if (Character.isWhitespace(c)) {
                    leadingWhitespace += c;
                } else {
                    break;
                }
            }

            // 提取结尾的空白字符
            for (int i = matchedContent.length() - 1; i >= 0; i--) {
                char c = matchedContent.charAt(i);
                if (c == '\n' || c == '\r' || Character.isWhitespace(c)) {
                    trailingWhitespace = c + trailingWhitespace;
                } else {
                    break;
                }
            }

            System.out.println("  匹配内容前导空白: " + leadingWhitespace.length() + " 字符 (" +
                    leadingWhitespace.replace("\n", "\\n").replace("\r", "\\r") + ")");
            System.out.println("  匹配内容尾随空白: " + trailingWhitespace.length() + " 字符 (" +
                    trailingWhitespace.replace("\n", "\\n").replace("\r", "\\r") + ")");
            System.out.println("✓ 已调整 newCodeBlock 缩进");

            // 组合：前导空白 + 调整后的代码 + 尾随空白
            String finalNewCode = leadingWhitespace + adjustedNewCode + trailingWhitespace;

            String result = targetCode.substring(0, startPos) + finalNewCode + targetCode.substring(endPos);

            System.out.println("✓ 替换完成!");
            System.out.println("  原始代码长度: " + targetCode.length());
            System.out.println("  替换后长度: " + result.length());

            return result;
        } else {
            System.out.println("✗ 未找到匹配内容");
            System.out.println("\n尝试使用备用匹配策略...");
            return tryAlternativeMatch(targetCode, oldCodeBlock, newCodeBlock);
        }
    }

    /**
     * 标准化字符串用于比较（移除所有空白差异）
     */
    private String normalizeForMatching(String code) {
        // 移除所有空白字符，只保留核心内容
        return code.replaceAll("\\s+", " ").trim();
    }
    /**
     * 计算两个字符串的相似度（基于编辑距离）
     */
    private double calculateSimilarity(String s1, String s2) {
        int maxLen = Math.max(s1.length(), s2.length());
        if (maxLen == 0) return 1.0;

        int distance = levenshteinDistance(s1, s2);
        return 1.0 - (double) distance / maxLen;
    }

    /**
     * 计算编辑距离
     */
    private int levenshteinDistance(String s1, String s2) {
        int[][] dp = new int[s1.length() + 1][s2.length() + 1];

        for (int i = 0; i <= s1.length(); i++) dp[i][0] = i;
        for (int j = 0; j <= s2.length(); j++) dp[0][j] = j;

        for (int i = 1; i <= s1.length(); i++) {
            for (int j = 1; j <= s2.length(); j++) {
                int cost = s1.charAt(i - 1) == s2.charAt(j - 1) ? 0 : 1;
                dp[i][j] = Math.min(
                        Math.min(dp[i - 1][j] + 1, dp[i][j - 1] + 1),
                        dp[i - 1][j - 1] + cost
                );
            }
        }

        return dp[s1.length()][s2.length()];
    }

    /**
     * 构建灵活匹配的正则表达式
     * 将固定空白转换为灵活的空白匹配，处理标签结束符等特殊情况
     */
    private String buildFlexibleRegex(String oldCodeBlock) {
        StringBuilder regex = new StringBuilder();

        // 标准化处理：统一标签结束符的空格（ /> 和 />）
        String normalized = oldCodeBlock.replaceAll("\\s+/>", "/>");

        // 按非空白 token 分割
        String[] tokens = normalized.split("\\s+");

        for (int i = 0; i < tokens.length; i++) {
            if (i > 0) {
                // token 之间允许任意空白（包括换行）
                regex.append("\\s+");
            }
            // 转义正则特殊字符
            String token = Pattern.quote(tokens[i]);
            // 处理自闭合标签：/> 前可能有空格
            if (tokens[i].endsWith("/>")) {
                String prefix = tokens[i].substring(0, tokens[i].length() - 2);
                token = Pattern.quote(prefix) + "\\s*/"+ ">";
            }
            regex.append(token);
        }

        return regex.toString();
    }

    /**
     * 检测代码块的基础缩进
     */
    private String detectIndent(String code) {
        String[] lines = code.split("\n");
        for (String line : lines) {
            if (!line.trim().isEmpty()) {
                int spaces = 0;
                for (char c : line.toCharArray()) {
                    if (c == ' ') spaces++;
                    else break;
                }
                return " ".repeat(spaces);
            }
        }
        return "";
    }

    /**
     * 调整代码缩进以匹配目标位置
     */
    private String adjustIndent(String code, String targetIndent) {
        String[] lines = code.split("\n", -1); // 保留尾部空行

        // 检测原始代码的最小缩进（基准缩进）
        int minIndent = Integer.MAX_VALUE;
        for (String line : lines) {
            if (!line.trim().isEmpty()) {
                int spaces = countLeadingSpaces(line);
                minIndent = Math.min(minIndent, spaces);
            }
        }
        if (minIndent == Integer.MAX_VALUE) minIndent = 0;

        System.out.println("  检测到原始代码最小缩进: " + minIndent + " 空格");
        System.out.println("  目标位置缩进: " + targetIndent.length() + " 空格");

        // 重新调整每行缩进
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) result.append("\n");

            String line = lines[i];
            if (line.trim().isEmpty()) {
                // 空行保持为空
                result.append("");
            } else {
                // 计算当前行缩进
                int currentIndent = countLeadingSpaces(line);
                // 相对于最小缩进的额外缩进
                int relativeIndent = currentIndent - minIndent;
                // 应用目标缩进 + 相对缩进
                String indent = targetIndent + " ".repeat(Math.max(0, relativeIndent));
                result.append(indent).append(line.trim());
            }
        }

        return result.toString();
    }

    /**
     * 计算行首空格数
     */
    private int countLeadingSpaces(String line) {
        int count = 0;
        for (char c : line.toCharArray()) {
            if (c == ' ') count++;
            else if (c == '\t') count += 4; // tab 算 4 个空格
            else break;
        }
        return count;
    }

    /**
     * 备用匹配策略：基于内容相似度的精确匹配
     */
    private String tryAlternativeMatch(String targetCode, String oldCodeBlock, String newCodeBlock) {
        System.out.println("\n" + "=".repeat(80));
        System.out.println("【备用策略: 基于内容相似度匹配】");
        System.out.println("=".repeat(80));

        // 提取 oldCodeBlock 的标准化内容（移除空白）
        String normalizedOld = normalizeForMatching(oldCodeBlock);

        String[] targetLines = targetCode.split("\n", -1);
        String[] oldLines = oldCodeBlock.split("\n");

        // 计算需要匹配的行数范围
        int oldNonEmptyLines = 0;
        for (String line : oldLines) {
            if (!line.trim().isEmpty()) oldNonEmptyLines++;
        }

        System.out.println("oldCodeBlock 有效行数: " + oldNonEmptyLines);

        int startLine = -1;
        int endLine = -1;
        double bestSimilarity = 0.0;

        // 滑动窗口查找最佳匹配
        for (int i = 0; i < targetLines.length; i++) {
            for (int j = i + oldNonEmptyLines - 1; j < Math.min(targetLines.length, i + oldNonEmptyLines + 10); j++) {
                // 提取窗口内容
                StringBuilder window = new StringBuilder();
                for (int k = i; k <= j; k++) {
                    window.append(targetLines[k]).append("\n");
                }

                String normalizedWindow = normalizeForMatching(window.toString());
                double similarity = calculateSimilarity(normalizedOld, normalizedWindow);

                if (similarity > bestSimilarity) {
                    bestSimilarity = similarity;
                    startLine = i;
                    endLine = j;
                }
            }
        }

        System.out.println("最佳匹配相似度: " + String.format("%.2f%%", bestSimilarity * 100));

        if (bestSimilarity < 0.8) {
            System.out.println("✗ 相似度过低（< 80%），匹配失败");
            return targetCode;
        }

        if (startLine >= 0 && endLine >= startLine) {
            System.out.println("✓ 找到匹配范围: 第 " + (startLine + 1) + " 行到第 " + (endLine + 1) + " 行");

            // 获取缩进
            String indent = detectIndent(targetLines[startLine]);
            String adjustedNewCode = adjustIndent(newCodeBlock, indent);

            // 重建代码
            StringBuilder result = new StringBuilder();
            for (int i = 0; i < startLine; i++) {
                result.append(targetLines[i]).append("\n");
            }
            result.append(adjustedNewCode);
            for (int i = endLine + 1; i < targetLines.length; i++) {
                result.append("\n").append(targetLines[i]);
            }

            System.out.println("✓ 备用策略替换成功!");
            return result.toString();
        }

        System.out.println("✗ 备用策略也未能匹配");
        return targetCode;
    }
}
