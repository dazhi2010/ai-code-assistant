package cn.com.wewell.aicodeassistant.util;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.*;
import com.intellij.psi.PsiFileFactory;
import com.intellij.ide.highlighter.JavaFileType;

import java.io.IOException;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 跨语言“紧凑模式”内容构建器（线程安全版、支持内部类递归）：
 * - 所有 PSI 访问均在 ReadAction 中执行，避免 Read access 断言异常。
 * - Java：仅保留 package、类/接口/枚举/注解声明、字段声明（去初始化）、方法签名（体省略），并递归包含内部类。
 *   同时过滤 Lombok/合成方法（getter/setter/equals/hashCode/构造等），避免浪费 token。
 *   imports 整体省略，AI 如需请通过 requires 请求。
 * - JS/TS：移除 import/require，保留函数/类签名，省略函数/方法/箭头函数体。
 * - HTML：移除注释，收缩标签间空白。
 * - CSS：移除 @import 与注释，保留选择器并省略规则体。
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
                    // 所有 PSI 相关逻辑放入 ReadAction；若无法直接获取 PSI，则用文本创建临时 PSI 再压缩；最后再做文本级兜底
                    return ApplicationManager.getApplication().runReadAction((Computable<String>) () -> {
                        if (project.isDisposed()) return safeLoad(file);
                        PsiFile psiFile = PsiManager.getInstance(project).findFile(file);
                        if (psiFile instanceof PsiJavaFile javaFile) {
                            return compactJava(javaFile);
                        }
                        // Fallback 1：用当前文本构造临时 Java PSI，再走同样的紧凑化逻辑
                        String text = safeLoad(file);
                        PsiFile tmp = PsiFileFactory.getInstance(project).createFileFromText("Dummy.java", JavaFileType.INSTANCE, text);
                        if (tmp instanceof PsiJavaFile tmpJava) {
                            return compactJava((PsiJavaFile) tmp);
                        }
                        // Fallback 2：文本级最小化（移除 import，收敛空行）
                        return compactJavaByText(text);
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
        // imports 省略，减少篇幅（AI 若需要请在 requires 中请求补充）

        // 顶层类与接口，递归渲染内部类
        PsiClass[] topLevel = file.getClasses();
        for (int i = 0; i < topLevel.length; i++) {
            renderClass(topLevel[i], out, "");
            if (i < topLevel.length - 1) out.append('\n');
        }
        return out.toString();
    }

    private static void renderClass(PsiClass cls, StringBuilder out, String indent) {
        // 类/接口/枚举/注解头部（包含注解与修饰符）
        if (cls.getModifierList() != null && cls.getModifierList().getTextLength() > 0) {
            out.append(indent).append(cls.getModifierList().getText()).append(' ');
        } else {
            out.append(indent);
        }
        String kind = cls.isAnnotationType() ? "@interface" : (cls.isInterface() ? "interface" : (cls.isEnum() ? "enum" : "class"));
        out.append(kind).append(' ').append(cls.getName());
        if (cls.getExtendsList() != null && cls.getExtendsList().getTextLength() > 0) {
            out.append(' ').append(cls.getExtendsList().getText());
        }
        if (cls.getImplementsList() != null && cls.getImplementsList().getTextLength() > 0) {
            out.append(' ').append(cls.getImplementsList().getText());
        }
        out.append(" {\n");

        // 枚举常量（仅输出名称列表，末尾加分号）
        if (cls.isEnum()) {
            PsiField[] fs = cls.getFields();
            StringBuilder constLine = new StringBuilder();
            for (PsiField f : fs) {
                if (f instanceof PsiEnumConstant ec) {
                    if (constLine.length() == 0) constLine.append(indent).append("    ");
                    else constLine.append(", ");
                    constLine.append(ec.getName());
                }
            }
            if (constLine.length() > 0) {
                constLine.append(";\n\n");
                out.append(constLine);
            }
        }

        // 字段：仅保留声明（去掉初始化，避免长字面量），跳过枚举常量字段
        for (PsiField f : cls.getFields()) {
            if (f instanceof PsiEnumConstant) continue;
            String decl = f.getText();
            if (f.getInitializer() != null) {
                decl = decl.replaceFirst("=\\s*.+?;", ";");
            }
            String[] lines = decl.split("\\r?\\n");
            for (String line : lines) {
                if (line.isBlank()) continue;
                out.append(indent).append("    ").append(line.trim()).append('\n');
            }
        }
        if (cls.getFields().length > 0) out.append('\n');

        // 方法：仅保留签名（体省略）；过滤 Lombok/合成方法
        PsiMethod[] methods = cls.getMethods();
        int emitted = 0;
        for (PsiMethod m : methods) {
            if (shouldSkipMethod(cls, m)) continue;
            out.append(indent).append("    ").append(buildMethodSignature(m)).append(" { /* body omitted */ }\n");
            emitted++;
        }
        if (emitted > 0) out.append('\n');

        // 内部类递归渲染
        for (PsiClass inner : cls.getInnerClasses()) {
            renderClass(inner, out, indent + "    ");
            out.append('\n');
        }

        out.append(indent).append("}\n");
    }

    private static String buildMethodSignature(PsiMethod m) {
        String mods = m.getModifierList() != null ? (m.getModifierList().getText() + ' ') : "";
        String ret = m.isConstructor() ? "" : (m.getReturnType() != null ? (m.getReturnType().getPresentableText() + ' ') : "");
        String name = m.getName();
        String params = Arrays.stream(m.getParameterList().getParameters())
                .map(p -> p.getType().getPresentableText() + " " + p.getName())
                .collect(Collectors.joining(", "));
        String throwsPart = m.getThrowsList() != null && m.getThrowsList().getTextLength() > 0 ? (" " + m.getThrowsList().getText()) : "";
        return mods + ret + name + "(" + params + ")" + throwsPart;
    }

    // 过滤 Lombok 生成或非物理（合成）的 PSI 方法，避免把 get/set/equals/hashCode 等方法加入紧凑内容
    private static boolean shouldSkipMethod(PsiClass cls, PsiMethod m) {
        // 非物理（如 Lombok Light 方法）或不在当前类文本范围内，直接跳过
        if (!m.isPhysical() || m.getTextRange() == null || cls.getTextRange() == null || !m.getTextRange().intersects(cls.getTextRange())) {
            return true;
        }
        // 类上存在 Lombok 注解时，进一步按名称/签名特征过滤典型生成方法
        if (hasLombokAnnotation(cls) && isLikelyLombokGenerated(m)) {
            return true;
        }
        return false;
    }

    private static boolean hasLombokAnnotation(PsiClass cls) {
        PsiModifierList ml = cls.getModifierList();
        if (ml == null) return false;
        for (PsiAnnotation ann : ml.getAnnotations()) {
            String qn = ann.getQualifiedName();
            if (qn != null) {
                if (qn.startsWith("lombok.")) return true;
                int idx = qn.lastIndexOf('.');
                String shortName = idx >= 0 ? qn.substring(idx + 1) : qn;
                if (isLombokShort(shortName)) return true;
            } else {
                PsiJavaCodeReferenceElement ref = ann.getNameReferenceElement();
                String shortName = ref != null ? ref.getText() : null;
                if (isLombokShort(shortName)) return true;
            }
        }
        return false;
    }

    private static boolean isLombokShort(String name) {
        if (name == null) return false;
        return name.equals("Data") || name.equals("Getter") || name.equals("Setter") ||
               name.equals("EqualsAndHashCode") || name.equals("ToString") || name.equals("Value") ||
               name.equals("Builder") || name.equals("SuperBuilder") || name.equals("With") ||
               name.equals("NoArgsConstructor") || name.equals("AllArgsConstructor") ||
               name.equals("RequiredArgsConstructor") || name.equals("FieldNameConstants") ||
               name.equals("UtilityClass");
    }

    private static boolean isLikelyLombokGenerated(PsiMethod m) {
        String n = m.getName();
        if (m.isConstructor()) return true; // 各种 *ArgsConstructor
        if ("toString".equals(n) || "hashCode".equals(n) || "equals".equals(n) || "canEqual".equals(n) ||
            "builder".equals(n) || "toBuilder".equals(n)) return true;
        int pc = m.getParameterList().getParametersCount();
        if (n.startsWith("get") && n.length() > 3 && pc == 0) return true;
        if (n.startsWith("is")  && n.length() > 2 && pc == 0) return true;
        if (n.startsWith("set") && n.length() > 3 && (pc == 1)) return true; // 常见链式 setter 也可被过滤
        return false;
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

    // 文本级 Java 紧凑化兜底：去掉 import 行，收敛空行
    private static String compactJavaByText(String code) {
        if (code == null) return "";
        String s = code;
        // 删除所有 import 语句（包括 static import）
        s = s.replaceAll("(?m)^\\s*import\\b[\\s\\S]*?;\\s*", "");
        // 收敛多余空行
        s = s.replaceAll("\n{3,}", "\n\n");
        return s;
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
