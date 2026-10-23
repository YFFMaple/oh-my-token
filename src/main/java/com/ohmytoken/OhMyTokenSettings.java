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
        /** 选定日期（yyyy-MM-dd），空 = 按范围统计 */
        public String customDate = "";
        /** 5 小时限额（token 数），0 = 不显示 */
        public long fiveHourLimit = 0;
        /** 状态栏显示速率 */
        public boolean showRate = true;
        /** 状态栏显示区间总量 */
        public boolean showTotal = true;
        /** Claude 数据目录，空 = 默认 ~/.claude/projects（改后需重启 IDE） */
        public String projectsDir = "";
        /** 会话上下文窗口大小（token 数） */
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
        this.state = state;
    }
}
