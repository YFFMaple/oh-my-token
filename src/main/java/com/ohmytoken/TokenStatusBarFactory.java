package com.ohmytoken;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.wm.StatusBar;
import com.intellij.openapi.wm.StatusBarWidget;
import com.intellij.openapi.wm.StatusBarWidgetFactory;
import org.jetbrains.annotations.NotNull;

final class TokenStatusBarFactory implements StatusBarWidgetFactory {

    @Override
    public @NotNull String getId() {
        return TokenStatsWidget.WIDGET_ID;
    }

    @Override
    public @NotNull String getDisplayName() {
        return "Token 速率与消耗 (Claude Code)";
    }

    @Override
    public boolean isAvailable(@NotNull Project project) {
        return true;
    }

    @Override
    public @NotNull StatusBarWidget createWidget(@NotNull Project project) {
        return new TokenStatsWidget();
    }

    @Override
    public void disposeWidget(@NotNull StatusBarWidget widget) {
        Disposer.dispose((TokenStatsWidget) widget);
    }

    @Override
    public boolean canBeEnabledOn(@NotNull StatusBar statusBar) {
        return true;
    }
}
