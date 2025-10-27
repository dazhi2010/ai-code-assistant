package cn.com.wewell.aicodeassistant.util;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.*;

import java.io.IOException;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 跨语言“紧凑模式”内容构建器（线程安全版）：
 * - 所有 PSI 访问均在 ReadAction 中执行，避免 Read access 断言异常。
 * - Java：仅保留 package/import、类声明、字段声明、方法签名（方法体省略）。
 * - JS/TS：保留 import/export、函数/类签名，方法/函数体替换为注释；箭头函数体省略。
 * - HTML：移除注释，收缩标签间空白。
 * - CSS：移除注释，保留选择器并省略规则体。
 * - JSON：最小化（忽略字符串内空白）。
 * - XML：移除注释，收缩标签间空白。
 */
public final class CompactContentBuilder {

    private CompactContentBuilder() {}

    public static String buildCompact(Project project, VirtualFile file) {
        try {
            String ext = file.getExtension() != null ? file.getExtension().toLowerCase() : "";
            switch (ext) {
                case "java":
                    // 所有 PSI 相关逻辑放入 ReadAction
                    return ApplicationManager.getApplication().runReadAction((Computable<String>) () -> {
                        if (project.isDisposed()) return safeLoad(file);
                        PsiFile psiFile = PsiManager.getInstance(project).findFile(file);
                        if (psiFile instanceof PsiJavaFile javaFile) {
                            return compactJava(javaFile);
                        }
                        return safeLoad(file);
                    });
                case "js": case "jsx": case "mjs": case "cjs":
                case "ts": case "tsx":
                    return compactJsTs(safeLoad(file));
                case "html": case "htm":
                    return compactHtml(safeLoad(file));
                case "css": case "scss": case "less":
                    return compactCss(safeLoad(file));
                case "json":
                    return minifyJson(safeLoad(file));
                case "xml":
                    return compactXml(safeLoad(file));
                default:
                    return collapseBlankLines(safeLoad(file));
            }
        } catch (Throwable t) {
            // 兜底返回原文，避免阻塞
            return safeLoad(file);
        }
    }

    // ================= Java =================
    private static String compactJava(PsiJavaFile file) {
        StringBuilder out = new StringBuilder();
        // package
        String pkg = file.getPackageName();
        if (pkg != null && !pkg.isEmpty()) {
            out.append("package ").append(pkg).append(";\n\n");
        }
        // imports 省略，减少篇幅。AI 若需要请通过 requires 请求补充
        // 不输出任何 import 行
        // classes
        for (PsiClass cls : file.getClasses()) {
            if (cls.getModifierList() != null && cls.getModifierList().getTextLength() > 0) {
                out.append(cls.getModifierList().getText()).append(' ');
            }
            out.append("class ").append(cls.getName());
            if (cls.getExtendsList() != null && cls.getExtendsList().getTextLength() > 0) {
                out.append(' ').append(cls.getExtendsList().getText());
            }
            if (cls.getImplementsList() != null && cls.getImplementsList().getTextLength() > 0) {
                out.append(' ').append(cls.getImplementsList().getText());
            }
            out.append(" {\n");

            // 字段：仅保留声明（去除初始化，避免长字面量）
            for (PsiField f : cls.getFields()) {
                String decl = f.getText();
                if (f.getInitializer() != null) {
                    decl = decl.replaceFirst("=\\s*.+?;", ";");
                }
                out.append("    ").append(decl).append("\n");
            }
            if (cls.getFields().length > 0) out.append('\n');

            // 方法：仅保留签名，方法体省略
            for (PsiMethod m : cls.getMethods()) {
                out.append("    ").append(buildMethodSignature(m)).append(" { /* body omitted */ }\n");
            }

            out.append("}\n");
        }
        return out.toString();
    }

    private static String buildMethodSignature(PsiMethod m) {
        String mods = m.getModifierList() != null ? (m.getModifierList().getText() + ' ') : "";
        String ret = m.getReturnType() != null ? (m.getReturnType().getPresentableText() + ' ') : "";
        String name = m.getName();
        String params = Arrays.stream(m.getParameterList().getParameters())
                .map(p -> p.getType().getPresentableText() + " " + p.getName())
                .collect(Collectors.joining(", "));
        String throwsPart = m.getThrowsList() != null && m.getThrowsList().getTextLength() > 0 ? (" " + m.getThrowsList().getText()) : "";
        return mods + ret + name + "(" + params + ")" + throwsPart;
    }

    // ================= JS / TS =================
    private static String compactJsTs(String code) {
        if (code == null) return "";
        String s = code;
        // 移除 import/require 语句，减少篇幅
        s = s.replaceAll("(?m)^\\s*import\\b[\\s\\S]*?;\\s*", "");
        s = s.replaceAll("(?m)^\\s*(?:const|let|var)\\s+[A-Za-z_$][\\w$]*\\s*=\\s*require\\([^\\n;]*\\)\\s*;\\s*", "");
        // 去多行注释
        s = s.replaceAll("/\\*[\\s\\S]*?\\*/", "");
        // 类体省略：class X ... { ... } -> class X ... { /* body omitted */ }
        s = replaceAllNonGreedy(s, Pattern.compile("class\\s+([A-Za-z_$][\\w$]*)\\s*([^\\{]*)\\{[\\s\\S]*?\\}"),
                m -> "class " + m.group(1) + m.group(2) + "{ /* body omitted */ }");
        // 普通函数：function foo(a,b){...}
        s = replaceAllNonGreedy(s, Pattern.compile("function\\s+([A-Za-z_$][\\w$]*)\\s*\\(([^)]*)\\)\\s*\\{[\\s\\S]*?\\}"),
                m -> "function " + m.group(1) + "(" + m.group(2) + ") { /* body omitted */ }");
        // 箭头函数（块体）：const foo = (a)=>{...}
        s = replaceAllNonGreedy(s, Pattern.compile("((?:const|let|var)\\s+[A-Za-z_$][\\w$]*\\s*=\\s*)\\(([^)]*)\\)\\s*=>\\s*\\{[\\s\\S]*?\\}"),
                m -> m.group(1) + "(" + m.group(2) + ") => { /* body omitted */ }");
        // 箭头函数（表达式体）：const foo = (a)=>a+b;
        s = replaceAllNonGreedy(s, Pattern.compile("((?:const|let|var)\\s+[A-Za-z_$][\\w$]*\\s*=\\s*\\([^)]*\\)\\s*=>)\\s*([^\\{;\\n][^;\\n]*)"),
                m -> m.group(1) + " { /* body omitted */ }");
        // 收敛空行
        s = s.replaceAll("\\n{3,}", "\\n\\n");
        return s;
    }

    // ================= HTML =================
    private static String compactHtml(String code) {
        if (code == null) return "";
        String s = code;
        s = s.replaceAll("<!--[\\s\\S]*?-->", "");
        s = s.replaceAll(">\\s+<", "><");
        s = s.replaceAll("[\\t ]+", " ");
        s = s.replaceAll("\\n{3,}", "\\n\\n");
        return s.trim();
    }

    // ================= CSS =================
    private static String compactCss(String code) {
        if (code == null) return "";
        String s = code;
        // 移除 @import 规则，减少篇幅
        s = s.replaceAll("(?m)^\\s*@import\\b[^;]*;\\s*", "");
        s = s.replaceAll("/\\*[\\s\\S]*?\\*/", "");
        // 将每个规则体替换为空体，保留选择器
        Pattern p = Pattern.compile("([^\\{]+)\\{[\\s\\S]*?\\}");
        Matcher m = p.matcher(s);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String selector = m.group(1).trim();
            m.appendReplacement(sb, Matcher.quoteReplacement(selector + " { /* rules omitted */ }"));
        }
        m.appendTail(sb);
        String out = sb.toString().replaceAll("\\n{3,}", "\\n\\n");
        return out;
    }

    // ================= JSON =================
    private static String minifyJson(String code) {
        if (code == null) return "";
        StringBuilder out = new StringBuilder(code.length());
        boolean inStr = false;
        boolean esc = false;
        for (int i = 0; i < code.length(); i++) {
            char c = code.charAt(i);
            if (inStr) {
                out.append(c);
                if (esc) { esc = false; }
                else if (c == '\\') { esc = true; }
                else if (c == '"') { inStr = false; }
            } else {
                if (c == '"') { inStr = true; out.append(c); }
                else if (Character.isWhitespace(c)) { /* drop */ }
                else { out.append(c); }
            }
        }
        return out.toString();
    }

    // ================= XML =================
    private static String compactXml(String code) {
        if (code == null) return "";
        String s = code;
        s = s.replaceAll("<!--[\\s\\S]*?-->", "");
        s = s.replaceAll(">\\s+<", "><");
        s = s.replaceAll("[\\t ]+", " ");
        s = s.replaceAll("\\n{3,}", "\\n\\n");
        return s.trim();
    }

    private static String collapseBlankLines(String s) {
        if (s == null) return "";
        return s.replaceAll("\\n{3,}", "\\n\\n");
    }

    private static String safeLoad(VirtualFile file) {
        try {
            return VfsUtilCore.loadText(file);
        } catch (IOException e) {
            return "";
        }
    }

    @FunctionalInterface
    private interface Replacer { String apply(Matcher m); }

    private static String replaceAllNonGreedy(String s, Pattern p, Replacer r) {
        Matcher m = p.matcher(s);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement(r.apply(m)));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
