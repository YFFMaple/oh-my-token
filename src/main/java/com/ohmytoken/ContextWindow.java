package com.ohmytoken;

import java.util.Locale;

/**
 * 按模型名推断上下文窗口大小。
 * Claude Code 会在模型名后加 [1m] 标记 1M 上下文；其余按型号族判断。
 */
final class ContextWindow {

    static final long M1 = 1_000_000;
    static final long K200 = 200_000;

    private ContextWindow() {
    }

    /**
     * @return 窗口 token 数；无法识别返回 0
     */
    static long detect(String model) {
        if (model == null || model.isBlank()) {
            return 0;
        }
        String m = model.toLowerCase(Locale.ROOT);
        if (m.contains("[1m]")) {
            return M1;
        }
        if (m.contains("fable") || m.contains("mythos")) {
            return M1;
        }
        // 第 5 代全部是 1M
        if (m.contains("opus-5") || m.contains("sonnet-5") || m.contains("haiku-5")) {
            return M1;
        }
        // 4.6 起 Opus/Sonnet 升级到 1M；4.5 及以前是 200K
        if (m.contains("opus-4-6") || m.contains("opus-4-7") || m.contains("opus-4-8")
                || m.contains("sonnet-4-6")) {
            return M1;
        }
        if (m.contains("opus") || m.contains("sonnet") || m.contains("haiku")) {
            return K200;
        }
        return 0;
    }
}
