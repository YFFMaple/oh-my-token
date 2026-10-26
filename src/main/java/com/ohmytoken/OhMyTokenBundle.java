package com.ohmytoken;

import com.intellij.DynamicBundle;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.PropertyKey;

/**
 * 国际化文案，按 JVM 默认 Locale 加载 messages/OhMyTokenBundle[_zh_CN].properties。
 */
final class OhMyTokenBundle {

    private static final String BUNDLE = "messages.OhMyTokenBundle";
    private static final DynamicBundle INSTANCE = new DynamicBundle(OhMyTokenBundle.class, BUNDLE);

    static String message(
            @NotNull @PropertyKey(resourceBundle = BUNDLE) String key,
            Object... params) {
        return INSTANCE.getMessage(key, params);
    }
}
