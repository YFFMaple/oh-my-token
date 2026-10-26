package com.ohmytoken;

import com.intellij.icons.AllIcons;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.ui.popup.ListPopup;
import com.intellij.openapi.ui.popup.PopupStep;
import com.intellij.openapi.ui.popup.util.BaseListPopupStep;
import com.intellij.openapi.wm.StatusBar;
import com.intellij.openapi.wm.StatusBarWidget;
import com.intellij.ui.awt.RelativePoint;
import com.intellij.util.Consumer;
import com.intellij.util.concurrency.AppExecutorUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Icon;
import java.awt.event.MouseEvent;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 状态栏 token 统计组件，每 2 秒扫描一次 jsonl 并刷新显示。
 * 点击打开趋势图；显示内容在 Settings → Tools → Oh My Token 配置。
 */
final class TokenStatsWidget implements StatusBarWidget, StatusBarWidget.TextPresentation, Disposable {

    static final String WIDGET_ID = "OhMyTokenWidget";

    /** 通知组 id，需与 plugin.xml 中的 notificationGroup 一致 */
    static final String NOTIFICATION_GROUP = "OhMyToken.Alerts";
    /** 限额用量达到该百分比时弹通知；回落到 5 个百分点以下才允许再次提醒 */
    private static final int QUOTA_ALERT_PERCENT = 90;
    private static final int ALERT_HYSTERESIS = 5;

    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("MM-dd");

    private final TokenStats stats = new TokenStats();
    private final TranscriptWatcher watcher =
            new TranscriptWatcher(defaultProjectsDir(), stats);

    private volatile DisplayRange range = loadRange();
    /** 自定义范围起止，都为 null 时按 range 统计 */
    private volatile LocalDate rangeFrom;
    private volatile LocalDate rangeTo;
    private StatusBar statusBar;
    private ScheduledFuture<?> future;
    private volatile String text = "⚡ -- tok/s";
    private volatile String tooltip = OhMyTokenBundle.message("widget.loading");
    /** 已提醒过的阈值，用量回落后复位，避免每轮刷新重复弹窗 */
    private boolean fiveHourWarned;
    private boolean weeklyWarned;
    private boolean ctxWarned;

    @Override
    public @NotNull String ID() {
        return WIDGET_ID;
    }

    @Override
    public @Nullable WidgetPresentation getPresentation() {
        return this;
    }

    @Override
    public void install(@NotNull StatusBar statusBar) {
        this.statusBar = statusBar;
        future = AppExecutorUtil.getAppScheduledExecutorService()
                .scheduleWithFixedDelay(this::refresh, 0, 2, TimeUnit.SECONDS);
    }

    @Override
    public void dispose() {
        if (future != null) {
            future.cancel(false);
        }
    }

    private void refresh() {
        watcher.scan();
        OhMyTokenSettings.State st = OhMyTokenSettings.getInstance().getState();
        range = DisplayRange.byName(st.displayRange);
        rangeFrom = parseDate(st.customFrom);
        rangeTo = parseDate(st.customTo);
        if (rangeFrom == null || rangeTo == null) {
            rangeFrom = null;
            rangeTo = null;
        } else if (rangeFrom.isAfter(rangeTo)) {
            LocalDate tmp = rangeFrom;
            rangeFrom = rangeTo;
            rangeTo = tmp;
        }
        TokenStats.Snapshot snap = stats.snapshot(range, rangeFrom, rangeTo);
        maybeNotify(snap, st);

        if (snap.lastEventMillis() == 0) {
            text = "⚡ -- tok/s";
            tooltip = OhMyTokenBundle.message("widget.no.data", defaultProjectsDir());
        } else {
            text = buildText(snap, st);
            tooltip = buildTooltip(snap, st);
        }
        StatusBar bar = statusBar;
        if (bar != null) {
            bar.updateWidget(ID());
        }
    }

    private String buildText(TokenStats.Snapshot snap, OhMyTokenSettings.State st) {
        List<String> parts = new ArrayList<>(3);
        if (st.showRate) {
            parts.add("⚡ " + formatCount(Math.round(snap.outputPerSec())) + " tok/s");
        }
        if (st.showTotal) {
            parts.add(rangeLabel() + " " + formatCount(snap.rangeTotal()));
        }
        if (st.fiveHourLimit > 0) {
            int pct = percent(snap.fiveHourUsed(), st.fiveHourLimit);
            parts.add((pct >= QUOTA_ALERT_PERCENT ? "⚠️ " : "") + "5h " + Math.min(pct, 999) + "%");
        }
        if (st.weeklyLimit > 0) {
            int pct = percent(snap.sevenDayUsed(), st.weeklyLimit);
            parts.add((pct >= QUOTA_ALERT_PERCENT ? "⚠️ " : "") + "7d " + Math.min(pct, 999) + "%");
        }
        long window = contextWindow(snap, st);
        if (snap.contextTokens() > 0 && window > 0) {
            int ctxPct = (int) (snap.contextTokens() * 100 / window);
            if (ctxPct >= st.ctxWarnPercent) {
                parts.add("⚠️ ctx " + ctxPct + "%");
            }
        }
        return parts.isEmpty()
                ? OhMyTokenBundle.message("widget.fallback")
                : String.join(" · ", parts);
    }

    private String buildTooltip(TokenStats.Snapshot snap, OhMyTokenSettings.State st) {
        List<String> lines = new ArrayList<>();
        lines.add(OhMyTokenBundle.message("widget.tooltip.rate",
                String.format("%.1f", snap.outputPerSec())));
        lines.add(OhMyTokenBundle.message("widget.tooltip.range",
                rangeLabel(),
                formatCount(snap.rangeInput()),
                formatCount(snap.rangeOutput()),
                formatCount(snap.rangeCache())));
        if (st.fiveHourLimit > 0) {
            int pct = percent(snap.fiveHourUsed(), st.fiveHourLimit);
            lines.add(OhMyTokenBundle.message("widget.tooltip.5h",
                    formatCount(snap.fiveHourUsed()), formatCount(st.fiveHourLimit), pct));
            if (snap.recentTotalPerMin() > 0) {
                long minutes = Math.max(0, Math.round(
                        (st.fiveHourLimit - snap.fiveHourUsed()) / snap.recentTotalPerMin()));
                lines.add(OhMyTokenBundle.message("widget.tooltip.5h.projection", minutes));
            } else {
                lines.add(OhMyTokenBundle.message("widget.tooltip.5h.idle"));
            }
            addReleaseLine(lines, "widget.tooltip.5h.release", snap.fiveHourRelease());
        }
        if (st.weeklyLimit > 0) {
            int pct = percent(snap.sevenDayUsed(), st.weeklyLimit);
            lines.add(OhMyTokenBundle.message("widget.tooltip.7d",
                    formatCount(snap.sevenDayUsed()), formatCount(st.weeklyLimit), pct));
            if (snap.recentTotalPerMin() > 0) {
                long minutes = Math.max(0, Math.round(
                        (st.weeklyLimit - snap.sevenDayUsed()) / snap.recentTotalPerMin()));
                lines.add(OhMyTokenBundle.message("widget.tooltip.7d.projection", minutes));
            }
            addReleaseLine(lines, "widget.tooltip.7d.release", snap.sevenDayRelease());
        }
        String models = formatModels(snap.models());
        if (!models.isEmpty()) {
            lines.add(OhMyTokenBundle.message("widget.tooltip.models", models));
        }
        String projects = formatProjects(snap.projects());
        if (!projects.isEmpty()) {
            lines.add(OhMyTokenBundle.message("widget.tooltip.projects", projects));
        }
        if (snap.lastModel() != null && !snap.lastModel().isBlank()) {
            lines.add(OhMyTokenBundle.message("widget.tooltip.model",
                    snap.lastModel().replaceFirst("^claude-", "")));
        }
        long window = contextWindow(snap, st);
        if (snap.contextTokens() > 0 && window > 0) {
            int ctxPct = (int) (snap.contextTokens() * 100 / window);
            lines.add(OhMyTokenBundle.message("widget.tooltip.ctx",
                    formatCount(snap.contextTokens()), formatCount(window), ctxPct));
        }
        lines.add(OhMyTokenBundle.message("widget.tooltip.last",
                TIME_FMT.format(Instant.ofEpochMilli(snap.lastEventMillis())))
                + OhMyTokenBundle.message(snap.active() ? "widget.tooltip.active" : "widget.tooltip.inactive"));
        lines.add(OhMyTokenBundle.message("widget.tooltip.click"));
        // 状态栏 tooltip 走 HTML 渲染，\n 不会换行，要用 <br>
        return "<html>" + String.join("<br>", lines) + "</html>";
    }

    /** 限额释放提示："约 1h 20m 后释放 ~12.3k tokens" */
    private static void addReleaseLine(List<String> lines, String key, TokenStats.Release release) {
        if (!release.present()) {
            return;
        }
        long remaining = Math.max(0, release.atMillis() - System.currentTimeMillis());
        lines.add(OhMyTokenBundle.message(key,
                formatDuration(remaining), formatCount(release.amount())));
    }

    /** 超过阈值时弹一次通知，回落到阈值以下后复位；每个阈值只提醒一次 */
    private void maybeNotify(TokenStats.Snapshot snap, OhMyTokenSettings.State st) {
        if (!st.notifyAlerts) {
            fiveHourWarned = weeklyWarned = ctxWarned = false;
            return;
        }
        if (st.fiveHourLimit > 0 && snap.fiveHourUsed() > 0) {
            int pct = percent(snap.fiveHourUsed(), st.fiveHourLimit);
            if (pct >= QUOTA_ALERT_PERCENT && !fiveHourWarned) {
                fiveHourWarned = true;
                notifyAlert(OhMyTokenBundle.message("alert.5h", pct,
                        formatCount(snap.fiveHourUsed()), formatCount(st.fiveHourLimit)));
            } else if (pct < QUOTA_ALERT_PERCENT - ALERT_HYSTERESIS) {
                fiveHourWarned = false;
            }
        }
        if (st.weeklyLimit > 0 && snap.sevenDayUsed() > 0) {
            int pct = percent(snap.sevenDayUsed(), st.weeklyLimit);
            if (pct >= QUOTA_ALERT_PERCENT && !weeklyWarned) {
                weeklyWarned = true;
                notifyAlert(OhMyTokenBundle.message("alert.7d", pct,
                        formatCount(snap.sevenDayUsed()), formatCount(st.weeklyLimit)));
            } else if (pct < QUOTA_ALERT_PERCENT - ALERT_HYSTERESIS) {
                weeklyWarned = false;
            }
        }
        long window = contextWindow(snap, st);
        if (window > 0 && snap.contextTokens() > 0) {
            int pct = percent(snap.contextTokens(), window);
            if (pct >= st.ctxWarnPercent && !ctxWarned) {
                ctxWarned = true;
                notifyAlert(OhMyTokenBundle.message("alert.ctx", pct,
                        formatCount(snap.contextTokens()), formatCount(window)));
            } else if (pct < st.ctxWarnPercent - ALERT_HYSTERESIS) {
                ctxWarned = false;
            }
        }
    }

    private static void notifyAlert(String content) {
        new Notification(NOTIFICATION_GROUP,
                OhMyTokenBundle.message("alert.title"), content, NotificationType.WARNING)
                .notify(null);
    }

    private static int percent(long used, long limit) {
        return limit <= 0 ? 0 : (int) (used * 100 / limit);
    }

    /** 上下文窗口大小：优先按模型自动识别，识别不出用设置值兜底 */
    private static long contextWindow(TokenStats.Snapshot snap, OhMyTokenSettings.State st) {
        if (st.autoContextWindow) {
            long detected = ContextWindow.detect(snap.lastModel());
            if (detected > 0) {
                return detected;
            }
        }
        return st.contextWindow;
    }

    private static String formatDuration(long millis) {
        long minutes = Math.max(0, Math.round(millis / 60_000.0));
        long days = minutes / (24 * 60);
        long hours = minutes / 60 % 24;
        long mins = minutes % 60;
        if (days > 0) {
            return days + "d " + hours + "h";
        }
        if (hours > 0) {
            return hours + "h " + mins + "m";
        }
        return mins + "m";
    }

    /** "sonnet 1.2M · opus 0.3M"，按总量降序，最多 5 个 */
    private static String formatModels(Map<String, long[]> models) {
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, long[]> e : models.entrySet()) {
            if (parts.size() >= 5) {
                break;
            }
            long[] b = e.getValue();
            String name = e.getKey().replaceFirst("^claude-", "");
            parts.add(name + " " + formatCount(b[0] + b[1] + b[2] + b[3]));
        }
        return String.join(" · ", parts);
    }

    /** 项目名取 slug 最后一段："d--GoWorkspace-oh-my-token" → "oh-my-token" */
    private static String formatProjects(Map<String, long[]> projects) {
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, long[]> e : projects.entrySet()) {
            if (parts.size() >= 3) {
                break;
            }
            long[] b = e.getValue();
            String slug = e.getKey();
            int i = slug.lastIndexOf("--");
            String name = i >= 0 && i + 2 < slug.length() ? slug.substring(i + 2) : slug;
            parts.add(name + " " + formatCount(b[0] + b[1] + b[2] + b[3]));
        }
        return String.join(" · ", parts);
    }

    // ---- TextPresentation ----

    @Override
    public @NotNull String getText() {
        return text;
    }

    @Override
    public @Nullable String getTooltipText() {
        return tooltip;
    }

    @Override
    public @Nullable Consumer<MouseEvent> getClickConsumer() {
        return this::showRangePopup;
    }

    @Override
    public float getAlignment() {
        return 0.5f; // 状态栏中部
    }

    /** 状态栏/提示里的范围标签：自定义范围显示 MM-dd~MM-dd，否则用范围名 */
    private String rangeLabel() {
        LocalDate from = rangeFrom, to = rangeTo;
        return from != null && to != null ? formatRange(from, to) : range.label();
    }

    private static String formatRange(LocalDate from, LocalDate to) {
        return from.equals(to)
                ? from.format(DATE_FMT)
                : from.format(DATE_FMT) + "~" + to.format(DATE_FMT);
    }

    private static LocalDate parseDate(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(s.trim());
        } catch (Exception e) {
            return null;
        }
    }

    private void showRangePopup(MouseEvent e) {
        // 4 个范围项 + 自定义日期范围 + 趋势图入口
        String dateItem = OhMyTokenBundle.message("popup.pickrange");
        List<Object> items = new ArrayList<>(List.of(DisplayRange.values()));
        items.add(dateItem);
        items.add(OhMyTokenBundle.message("popup.chart"));
        ListPopup popup = JBPopupFactory.getInstance().createListPopup(
                new BaseListPopupStep<>(OhMyTokenBundle.message("popup.title"), items) {
                    /** onChosen 里不能直接弹对话框（焦点问题），交给 popup 关闭后执行 */
                    private Runnable pending;

                    @Override
                    public @NotNull String getTextFor(Object value) {
                        if (value instanceof DisplayRange r) {
                            return r.label();
                        }
                        String s = (String) value;
                        // 日期项后面带上当前选定的范围
                        if (s.equals(dateItem) && rangeFrom != null && rangeTo != null) {
                            return s + "  (" + formatRange(rangeFrom, rangeTo) + ")";
                        }
                        return s;
                    }

                    @Override
                    public @Nullable Icon getIconFor(Object value) {
                        boolean checked = value instanceof DisplayRange
                                ? rangeFrom == null && value == range
                                : value.equals(dateItem) && rangeFrom != null;
                        return checked ? AllIcons.Actions.Checked : null;
                    }

                    @Override
                    public PopupStep<?> onChosen(Object selected, boolean finalChoice) {
                        OhMyTokenSettings.State st = OhMyTokenSettings.getInstance().getState();
                        if (selected instanceof DisplayRange r) {
                            range = r;
                            rangeFrom = null;
                            rangeTo = null;
                            st.displayRange = r.name();
                            st.customFrom = "";
                            st.customTo = "";
                            refresh();
                        } else if (selected.equals(dateItem)) {
                            pending = () -> {
                                RangeDialog dialog = new RangeDialog(rangeFrom, rangeTo);
                                if (dialog.showAndGet()) {
                                    rangeFrom = dialog.from();
                                    rangeTo = dialog.to();
                                    st.customFrom = rangeFrom.toString();
                                    st.customTo = rangeTo.toString();
                                    refresh();
                                }
                            };
                        } else {
                            // 趋势图对话框（打开前取最新快照）
                            pending = () -> {
                                watcher.scan();
                                new ChartDialog(stats,
                                        stats.snapshot(range, rangeFrom, rangeTo)).show();
                            };
                        }
                        return FINAL_CHOICE;
                    }

                    @Override
                    public @Nullable Runnable getFinalRunnable() {
                        return pending;
                    }
                });
        popup.show(new RelativePoint(e.getComponent(), e.getPoint()));
    }

    private static DisplayRange loadRange() {
        return DisplayRange.byName(OhMyTokenSettings.getInstance().getState().displayRange);
    }

    private static String formatCount(long n) {
        if (n >= 1_000_000) {
            return String.format("%.2fM", n / 1e6);
        }
        if (n >= 1_000) {
            return String.format("%.1fk", n / 1e3);
        }
        return Long.toString(n);
    }

    static Path defaultProjectsDir() {
        String fromSettings = OhMyTokenSettings.getInstance().getState().projectsDir;
        if (!fromSettings.isBlank()) {
            return Path.of(fromSettings);
        }
        String override = System.getProperty("ohmytoken.projectsDir");
        if (override != null && !override.isBlank()) {
            return Path.of(override);
        }
        return Path.of(System.getProperty("user.home"), ".claude", "projects");
    }
}
