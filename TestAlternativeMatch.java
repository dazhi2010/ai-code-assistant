public class TestAlternativeMatch {
    public static void main(String[] args) {
        String oldCodeBlock = "      <template #header>
" +
                "        <div class="card-header">
" +
                "          <span class="title">底层共享通道管理 (4.2.3.6 - 4.2.3.9)</span>
" +
                "          <el-button type="primary" @click="handleAdd">
" +
                "            <el-icon><Plus /></el-icon>新建通道实例
" +
                "          </el-button>
" +
                "        </div>
" +
                "      </template>
" +
                "
" +
                "      
" +
                "      <el-tabs v-model="activeTab" @tab-change="handleTabChange">
" +
                "        <el-tab-pane label="API 共享 (4.2.3.6)" name="api">
" +
                "          <el-alert title="通过API技术实现不同系统之间的数据交互和共享，减少系统之间的耦合度，提高系统的可扩展性和灵活性。" type="info" show-icon :closable="false" class="mb-20" />
" +
                "        </el-tab-pane>
" +
                "        <el-tab-pane label="数据库共享 (4.2.3.7)" name="database">
" +
                "          <el-alert title="构建分布式数据库或数据仓库，实现数据的集中存储和共享访问，提高数据的可用性和可靠性，并保障数据的IO效率满足业务系统性能需要。" type="success" show-icon :closable="false" class="mb-20" />
" +
                "        </el-tab-pane>
" +
                "        <el-tab-pane label="数据网关共享 (4.2.3.8)" name="gateway">
" +
                "          <el-alert title="通过数据网关实现不同系统之间的数据传输和转换，保障数据的正确性和一致性。" type="warning" show-icon :closable="false" class="mb-20" />
" +
                "        </el-tab-pane>
" +
                "        <el-tab-pane label="数据链接共享 (4.2.3.9)" name="datalink">
" +
                "          <el-alert title="通过数据链接技术实现不同系统之间的数据关联和共享，提高数据的可用性和可维护性。" type="error" show-icon :closable="false" class="mb-20" />
" +
                "        </el-tab-pane>
" +
                "      </el-tabs>";

        String targetCode = "<template>
" +
                "  <div class="channel-container">
" +
                "    <el-card class="box-card" shadow="never">
" +
                oldCodeBlock + "
" +
                "    </el-card>
" +
                "  </div>
" +
                "</template>";
                
        // intentionally break regex match
        String newCodeBlock = "NEW_CODE_BLOCK_CONTENT";
        String result = tryAlternativeMatch(targetCode, oldCodeBlock + " 
", newCodeBlock, "UPDATE");
        System.out.println("Result:
" + result);
    }

    private static String tryAlternativeMatch(String targetCode, String oldCodeBlock, String newCodeBlock, String actionType) {
        String normalizedOld = normalizeForMatching(oldCodeBlock);

        String[] targetLines = targetCode.split("
", -1);
        String[] oldLines = oldCodeBlock.split("
");

        int oldNonEmptyLines = 0;
        for (String line : oldLines) {
            if (!line.trim().isEmpty()) oldNonEmptyLines++;
        }

        int startLine = -1;
        int endLine = -1;
        double bestSimilarity = 0.0;

        for (int i = 0; i < targetLines.length; i++) {
            for (int j = i + Math.max(0, oldNonEmptyLines - 3); j < Math.min(targetLines.length, i + oldNonEmptyLines + 5); j++) {
                StringBuilder window = new StringBuilder();
                for (int k = i; k <= j; k++) {
                    window.append(targetLines[k]).append("
");
                }

                String normalizedWindow = normalizeForMatching(window.toString());
                
                int maxLen = Math.max(normalizedOld.length(), normalizedWindow.length());
                if (maxLen == 0) continue;
                int lenDiff = Math.abs(normalizedOld.length() - normalizedWindow.length());
                if (1.0 - (double) lenDiff / maxLen <= bestSimilarity) {
                    continue;
                }

                double similarity = calculateSimilarity(normalizedOld, normalizedWindow);

                if (similarity > bestSimilarity) {
                    bestSimilarity = similarity;
                    startLine = i;
                    endLine = j;
                }
            }
        }
        
        System.out.println("Best similarity: " + bestSimilarity + " startLine: " + startLine + " endLine: " + endLine);

        if (bestSimilarity < 0.8 || startLine < 0) {
            throw new RuntimeException("正则与相似度匹配均失败。最高相似度仅为: " + String.format("%.2f%%", bestSimilarity * 100));
        }

        String indent = "      ";
        String adjustedNewCode = newCodeBlock;

        // UPDATE
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < startLine; i++) result.append(targetLines[i]).append("
");
        result.append(adjustedNewCode);
        if (endLine + 1 < targetLines.length) result.append("
");
        for (int i = endLine + 1; i < targetLines.length; i++) {
            result.append(targetLines[i]);
            if (i < targetLines.length - 1) result.append("
");
        }

        return result.toString();
    }
    
    private static String normalizeForMatching(String code) {
        return code.replaceAll("\s+", " ")
                   .replace('"', ''')
                   .replace("/>", " />")
                   .replaceAll("\s+/>", " />")
                   .trim();
    }

    private static double calculateSimilarity(String s1, String s2) {
        int maxLen = Math.max(s1.length(), s2.length());
        if (maxLen == 0) return 1.0;
        int distance = levenshteinDistance(s1, s2);
        return 1.0 - (double) distance / maxLen;
    }

    private static int levenshteinDistance(String s1, String s2) {
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
}
