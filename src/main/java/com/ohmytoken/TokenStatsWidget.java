package com.ohmytoken;

import com.intellij.icons.AllIcons;
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
 * 点击弹出范围选择菜单；显示内容在 Settings → Tools → Oh My Token 配置。
 */
final class TokenStatsWidget implements StatusBarWidget, StatusBarWidget.TextPresentation, Disposable {

    static final String WIDGET_ID = "OhMyTokenWidget";

    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    private final TokenStats stats = new TokenStats();
    private final TranscriptWatcher watcher =
            new TranscriptWatcher(defaultProjectsDir(), stats);

    private volatile DisplayRange range = loadRange();
    private volatile LocalDate customDate = loadCustomDate();
    private StatusBar statusBar;
    private ScheduledFuture<?> future;
    private volatile String text = "⚡ -- tok/s";
    private volatile String tooltip = OhMyTokenBundle.message("widget.loading");

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
        customDate = parseDate(st.customDate);
        TokenStats.Snapshot snap = stats.snapshot(range, customDate);

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
            int pct = (int) (snap.fiveHourUsed() * 100 / st.fiveHourLimit);
            parts.add((pct >= 90 ? "⚠️ " : "") + "5h " + Math.min(pct, 999) + "%");
        }
        if (snap.contextTokens() > 0 && st.contextWindow > 0) {
            int ctxPct = (int) (snap.contextTokens() * 100 / st.contextWindow);
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
            int pct = (int) (snap.fiveHourUsed() * 100 / st.fiveHourLimit);
            lines.add(OhMyTokenBundle.message("widget.tooltip.5h",
                    formatCount(snap.fiveHourUsed()), formatCount(st.fiveHourLimit), pct));
            if (snap.recentTotalPerMin() > 0) {
                long minutes = Math.max(0, Math.round(
                        (st.fiveHourLimit - snap.fiveHourUsed()) / snap.recentTotalPerMin()));
                lines.add(OhMyTokenBundle.message("widget.tooltip.5h.projection", minutes));
            } else {
                lines.add(OhMyTokenBundle.message("widget.tooltip.5h.idle"));
            }
        }
        String models = formatModels(snap.models());
        if (!models.isEmpty()) {
            lines.add(OhMyTokenBundle.message("widget.tooltip.models", models));
        }
        String projects = formatProjects(snap.projects());
        if (!projects.isEmpty()) {
            lines.add(OhMyTokenBundle.message("widget.tooltip.projects", projects));
        }
        if (snap.contextTokens() > 0 && st.contextWindow > 0) {
            int ctxPct = (int) (snap.contextTokens() * 100 / st.contextWindow);
            lines.add(OhMyTokenBundle.message("widget.tooltip.ctx",
                    formatCount(snap.contextTokens()), formatCount(st.contextWindow), ctxPct));
        }
        lines.add(OhMyTokenBundle.message("widget.tooltip.last",
                TIME_FMT.format(Instant.ofEpochMilli(snap.lastEventMillis())))
                + OhMyTokenBundle.message(snap.active() ? "widget.tooltip.active" : "widget.tooltip.inactive"));
        lines.add(OhMyTokenBundle.message("widget.tooltip.click"));
        return String.join("\n", lines);
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
            parts.add(name + " " + formatCount(b[0] + b[1] + b[2]));
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
            parts.add(name + " " + formatCount(b[0] + b[1] + b[2]));
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

    /** 状态栏/提示里的范围标签：选定日期显示 MM-dd，否则用范围名 */
    private String rangeLabel() {
        LocalDate d = customDate;
        return d != null
                ? d.format(DateTimeFormatter.ofPattern("MM-dd"))
                : range.label();
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

    private static LocalDate loadCustomDate() {
        return parseDate(OhMyTokenSettings.getInstance().getState().customDate);
    }

    private void showRangePopup(MouseEvent e) {
        // 4 个范围项 + 选定日期 + 趋势图入口
        String dateItem = OhMyTokenBundle.message("popup.pickdate");
        List<Object> items = new ArrayList<>(List.of(DisplayRange.values()));
        items.add(dateItem);
        items.add(OhMyTokenBundle.message("popup.chart"));
        ListPopup popup = JBPopupFactory.getInstance().createListPopup(
                new BaseListPopupStep<>(OhMyTokenBundle.message("popup.title"), items) {
                    @Override
                    public @NotNull String getTextFor(Object value) {
                        if (value instanceof DisplayRange r) {
                            return r.label();
                        }
                        String s = (String) value;
                        // 日期项后面带上当前选定的日期
                        if (s.equals(dateItem) && customDate != null) {
                            return s + "  (" + customDate + ")";
                        }
                        return s;
                    }

                    @Override
                    public @Nullable Icon getIconFor(Object value) {
                        boolean checked = value instanceof DisplayRange
                                ? customDate == null && value == range
                                : value.equals(dateItem) && customDate != null;
                        return checked ? AllIcons.Actions.Checked : null;
                    }

                    @Override
                    public PopupStep<?> onChosen(Object selected, boolean finalChoice) {
                        OhMyTokenSettings.State st = OhMyTokenSettings.getInstance().getState();
                        if (selected instanceof DisplayRange r) {
                            range = r;
                            customDate = null;
                            st.displayRange = r.name();
                            st.customDate = "";
                            refresh();
                        } else if (selected.equals(dateItem)) {
                            DateDialog dialog = new DateDialog();
                            if (dialog.showAndGet()) {
                                customDate = dialog.date();
                                st.customDate = customDate.toString();
                                refresh();
                            }
                        } else {
                            // 趋势图对话框（打开前取最新快照）
                            watcher.scan();
                            new ChartDialog(stats.snapshot(range, customDate)).show();
                        }
                        return FINAL_CHOICE;
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
