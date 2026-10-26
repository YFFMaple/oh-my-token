package com.ohmytoken;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import org.jetbrains.annotations.NotNull;

/**
 * 插件设置，持久化到 oh-my-token.xml。
 */
@Service(Service.Level.APP)
@State(name = "OhMyTokenSettings", storages = @Storage("oh-my-token.xml"))
public final class OhMyTokenSettings implements PersistentStateComponent<OhMyTokenSettings.State> {

    public static class State {
        /** 统计范围：DisplayRange.name() */
        public String displayRange = "TODAY";
        /** 自定义日期范围起止（yyyy-MM-dd），都为空 = 按 displayRange 统计 */
        public String customFrom = "";
        public String customTo = "";
        /** 旧版本的单个选定日期，仅用于升级迁移 */
        public String customDate = "";
        /** 5 小时限额（token 数），0 = 不显示 */
        public long fiveHourLimit = 0;
        /** 7 天限额（token 数），0 = 不显示 */
        public long weeklyLimit = 0;
        /** 5h/7d 限额与上下文超阈值时弹通知 */
        public boolean notifyAlerts = true;
        /** 状态栏显示速率 */
        public boolean showRate = true;
        /** 状态栏显示区间总量 */
        public boolean showTotal = true;
        /** Claude 数据目录，空 = 默认 ~/.claude/projects（改后需重启 IDE） */
        public String projectsDir = "";
        /** 按当前模型自动识别上下文窗口大小 */
        public boolean autoContextWindow = true;
        /** 上下文窗口大小（token 数），自动识别失败时使用 */
        public long contextWindow = 200_000;
        /** 上下文占用告警阈值（%） */
        public int ctxWarnPercent = 80;
    }

    private State state = new State();

    public static OhMyTokenSettings getInstance() {
        return ApplicationManager.getApplication().getService(OhMyTokenSettings.class);
    }

    @Override
    public @NotNull State getState() {
        return state;
    }

    @Override
    public void loadState(@NotNull State state) {
        // 旧版本只存单个日期，升级时迁移成同一天的起止范围
        if (isBlank(state.customFrom) && isBlank(state.customTo) && !isBlank(state.customDate)) {
            state.customFrom = state.customDate;
            state.customTo = state.customDate;
            state.customDate = "";
        }
        this.state = state;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
