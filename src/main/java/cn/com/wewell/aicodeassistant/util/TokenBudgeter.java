package cn.com.wewell.aicodeassistant.util;

/**
 * Token 预算器（近似估算，按字符/4 折算为 token）。
 * 仅用于控制输入上下文大小，避免超限。
 */
public final class TokenBudgeter {

    private static final int CHARS_PER_TOKEN = 4; // 经验值近似

    private TokenBudgeter() {
    }

    /**
     * 估算文本 token 数（近似）。
     */
    public static int estimateTokens(CharSequence text) {
        if (text == null) return 0;
        int len = text.length();
        return (len + CHARS_PER_TOKEN - 1) / CHARS_PER_TOKEN;
    }

    /**
     * 判断 current + candidate 是否会超过给定 token 上限。
     */
    public static boolean willExceed(CharSequence current, CharSequence candidate, int limit) {
        return estimateTokens(current) + estimateTokens(candidate) > limit;
    }

    /**
     * 计算在给定上限下剩余可用 token 数。
     */
    public static int remainingBudget(CharSequence current, int limit) {
        int left = limit - estimateTokens(current);
        return Math.max(left, 0);
    }
}
