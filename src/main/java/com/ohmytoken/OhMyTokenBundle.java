package com.ohmytoken;

import com.intellij.DynamicBundle;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.PropertyKey;

/**
 * 国际化文案，按 JVM 默认 Locale 加载 messages/OhMyTokenBundle[_zh_CN].properties。
 */
final class OhMyTokenBundle extends DynamicBundle {

    private static final String BUNDLE = "messages.OhMyTokenBundle";
    private static final OhMyTokenBundle INSTANCE = new OhMyTokenBundle();

    private OhMyTokenBundle() {
        super(BUNDLE);
    }

    static String message(
            @NotNull @PropertyKey(resourceBundle = BUNDLE) String key,
            Object... params) {
        return INSTANCE.getMessage(key, params);
    }
}
