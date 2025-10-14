package cn.com.wewell.aicodeassistant.model;

import java.io.File;
import java.util.List;

public class FileChanges {
    private final String filePath;
    private final List<AiResponseAction> actions;

    public FileChanges(String filePath, List<AiResponseAction> actions) {
        this.filePath = filePath;
        this.actions = actions;
    }

    public List<AiResponseAction> getActions() {
        return actions;
    }

    @Override
    public String toString() {
        File file = new File(filePath);
        String fileName = file.getName();
        String parentPath = file.getParent() != null ? file.getParent().replace('\\', '/') + "/" : "";

        if (actions.size() == 1) {
            AiResponseAction firstAction = actions.get(0);
            String actionType = switch (firstAction.action().toUpperCase()) {
                case "CREATE" -> "创建";
                case "OVERWRITE" -> "覆盖";
                case "DELETE" -> "删除";
                default -> "更新";
            };
            return String.format("%s: %s [%s]", actionType, fileName, parentPath);
        } else {
            return String.format("更新: %s (%d 处) [%s]", fileName, actions.size(), parentPath);
        }
    }
}