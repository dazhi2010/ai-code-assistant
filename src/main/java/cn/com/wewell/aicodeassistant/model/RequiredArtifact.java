package cn.com.wewell.aicodeassistant.model;

public record RequiredArtifact(Type type, String nameOrPath) {
    public enum Type { FILE, CLASS }
    @Override
    public String toString() {
        return type == Type.FILE ? "需要文件: " + nameOrPath : "需要类: " + nameOrPath;
    }
}