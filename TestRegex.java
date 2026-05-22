import java.util.regex.*;

public class TestRegex {
    public static void main(String[] args) {
        String oldCodeBlock = "      <template #header>\n" +
                "        <div class=\"card-header\">\n" +
                "          <span class=\"title\">底层共享通道管理 (4.2.3.6 - 4.2.3.9)</span>\n" +
                "          <el-button type=\"primary\" @click=\"handleAdd\">\n" +
                "            <el-icon><Plus /></el-icon>新建通道实例\n" +
                "          </el-button>\n" +
                "        </div>\n" +
                "      </template>\n" +
                "\n" +
                "      \n" +
                "      <el-tabs v-model=\"activeTab\" @tab-change=\"handleTabChange\">\n" +
                "        <el-tab-pane label=\"API 共享 (4.2.3.6)\" name=\"api\">\n" +
                "          <el-alert title=\"通过API技术实现不同系统之间的数据交互和共享，减少系统之间的耦合度，提高系统的可扩展性和灵活性。\" type=\"info\" show-icon :closable=\"false\" class=\"mb-20\" />\n" +
                "        </el-tab-pane>\n" +
                "        <el-tab-pane label=\"数据库共享 (4.2.3.7)\" name=\"database\">\n" +
                "          <el-alert title=\"构建分布式数据库或数据仓库，实现数据的集中存储和共享访问，提高数据的可用性和可靠性，并保障数据的IO效率满足业务系统性能需要。\" type=\"success\" show-icon :closable=\"false\" class=\"mb-20\" />\n" +
                "        </el-tab-pane>\n" +
                "        <el-tab-pane label=\"数据网关共享 (4.2.3.8)\" name=\"gateway\">\n" +
                "          <el-alert title=\"通过数据网关实现不同系统之间的数据传输和转换，保障数据的正确性和一致性。\" type=\"warning\" show-icon :closable=\"false\" class=\"mb-20\" />\n" +
                "        </el-tab-pane>\n" +
                "        <el-tab-pane label=\"数据链接共享 (4.2.3.9)\" name=\"datalink\">\n" +
                "          <el-alert title=\"通过数据链接技术实现不同系统之间的数据关联和共享，提高数据的可用性和可维护性。\" type=\"error\" show-icon :closable=\"false\" class=\"mb-20\" />\n" +
                "        </el-tab-pane>\n" +
                "      </el-tabs>";

        String targetCode = "<template>\n" +
                "  <div class=\"channel-container\">\n" +
                "    <el-card class=\"box-card\" shadow=\"never\">\n" +
                oldCodeBlock + "\n" +
                "    </el-card>\n" +
                "  </div>\n" +
                "</template>";
                
        String normalized = oldCodeBlock.replaceAll("\\s+/>", "/>");
        String[] tokens = normalized.trim().split("\\s+");
        StringBuilder regex = new StringBuilder();

        for (int i = 0; i < tokens.length; i++) {
            if (tokens[i].isEmpty()) continue;
            if (i > 0) regex.append("\\s+");

            String token = tokens[i];
            String escaped = escapeTokenForRegex(token);
            escaped = escaped.replace("/\\>", "\\s*/\\>");
            regex.append(escaped);
        }
        
        System.out.println("Regex:\n" + regex.toString() + "\n");
        Pattern pattern = Pattern.compile(regex.toString(), Pattern.DOTALL);
        Matcher matcher = pattern.matcher(targetCode);
        boolean found = matcher.find();
        System.out.println("Matches target exactly? " + found);
        
        if (found) {
            System.out.println("Matched Content:\n" + matcher.group());
        }
    }
    
    private static String escapeTokenForRegex(String token) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if (c == '"' || c == '\'') {
                sb.append("[\"']");
            } else if ("<>()[]{}\\^$|?*+.".indexOf(c) != -1) {
                sb.append("\\").append(c);
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
