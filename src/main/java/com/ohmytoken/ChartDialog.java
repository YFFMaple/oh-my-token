package com.ohmytoken;

import com.intellij.ide.actions.RevealFileAction;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationAction;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.fileChooser.FileChooserFactory;
import com.intellij.openapi.fileChooser.FileSaverDescriptor;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileWrapper;
import com.intellij.ui.JBColor;
import org.jetbrains.annotations.Nullable;

import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.ToolTipManager;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.RenderingHints;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 用量趋势图：今日逐小时 + 近 30 天逐日，纯 Swing 手绘堆叠柱状图。
 * 顶部工具栏：按项目筛选、导出 CSV。统计范围在状态栏点击菜单里切换。
 */
final class ChartDialog extends DialogWrapper {

    private final TokenStats stats;
    private final String allProjects;
    private final ChartPanel hourlyPanel;
    private final ChartPanel dailyPanel;
    private final JPanel chartsPanel;
    /** null = 全部项目，否则为项目 slug */
    private String project;

    ChartDialog(TokenStats stats, TokenStats.Snapshot snap) {
        super((com.intellij.openapi.project.Project) null);
        setTitle(OhMyTokenBundle.message("chart.dialog.title"));
        this.stats = stats;
        this.allProjects = OhMyTokenBundle.message("chart.project.all");

        String[] hourLabels = new String[24];
        for (int i = 0; i < 24; i++) {
            hourLabels[i] = String.format("%02d", i);
        }
        hourlyPanel = new ChartPanel(OhMyTokenBundle.message("chart.title.hourly"),
                snap.todayHourly(), hourLabels, false);

        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("MM-dd");
        LocalDate today = LocalDate.now();
        String[] dayLabels = new String[30];
        for (int i = 0; i < 30; i++) {
            dayLabels[i] = today.minusDays(29 - i).format(fmt);
        }
        dailyPanel = new ChartPanel(OhMyTokenBundle.message("chart.title.daily"),
                snap.daily30(), dayLabels, true);
        chartsPanel = new JPanel(new GridLayout(2, 1, 0, 12));
        chartsPanel.add(hourlyPanel);
        chartsPanel.add(dailyPanel);

        init();
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        JPanel root = new JPanel(new BorderLayout(0, 8));
        root.add(buildToolbar(), BorderLayout.NORTH);
        root.add(chartsPanel, BorderLayout.CENTER);
        root.setPreferredSize(new Dimension(720, 560));
        return root;
    }

    private JComponent buildToolbar() {
        JComboBox<String> projectCombo = buildProjectCombo();

        JButton exportButton = new JButton(OhMyTokenBundle.message("chart.export.csv"));
        exportButton.addActionListener(e -> exportCsv());

        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        toolbar.add(new JLabel(OhMyTokenBundle.message("chart.project.label")));
        toolbar.add(projectCombo);
        toolbar.add(exportButton);
        return toolbar;
    }

    private JComboBox<String> buildProjectCombo() {
        // 项目项展示 slug 最后一段，值仍是完整 slug
        JComboBox<String> combo = new JComboBox<>();
        combo.addItem(allProjects);
        TokenStats.Snapshot snap = stats.snapshot(DisplayRange.MONTH, null, null);
        snap.projects().keySet().forEach(combo::addItem);
        combo.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value,
                                                          int index, boolean isSelected, boolean cellHasFocus) {
                Object display = value;
                if (value instanceof String slug && !allProjects.equals(slug)) {
                    int i = slug.lastIndexOf("--");
                    if (i >= 0 && i + 2 < slug.length()) {
                        display = slug.substring(i + 2);
                    }
                }
                return super.getListCellRendererComponent(list, display, index, isSelected, cellHasFocus);
            }
        });
        combo.addActionListener(e -> {
            Object sel = combo.getSelectedItem();
            project = (sel == null || allProjects.equals(sel)) ? null : (String) sel;
            reloadCharts();
        });
        return combo;
    }

    /** 按当前项目筛选重载图表：项目视图没有逐小时数据，隐藏逐小时图 */
    private void reloadCharts() {
        TokenStats.Snapshot snap = stats.snapshot(DisplayRange.TODAY, null, null);
        long[][] daily = project == null
                ? snap.daily30()
                : stats.daily30ByProject(project);
        dailyPanel.setData(daily);
        dailyPanel.setTitle(project == null
                ? OhMyTokenBundle.message("chart.title.daily")
                : OhMyTokenBundle.message("chart.title.daily.project", shortSlug(project)));
        hourlyPanel.setData(snap.todayHourly());

        chartsPanel.removeAll();
        if (project == null) {
            chartsPanel.setLayout(new GridLayout(2, 1, 0, 12));
            chartsPanel.add(hourlyPanel);
            chartsPanel.add(dailyPanel);
        } else {
            chartsPanel.setLayout(new GridLayout(1, 1));
            chartsPanel.add(dailyPanel);
        }
        chartsPanel.revalidate();
        chartsPanel.repaint();
    }

    private static String shortSlug(String slug) {
        int i = slug.lastIndexOf("--");
        return i >= 0 && i + 2 < slug.length() ? slug.substring(i + 2) : slug;
    }

    private void exportCsv() {
        FileSaverDescriptor descriptor = new FileSaverDescriptor(
                OhMyTokenBundle.message("export.dialog.title"), "", "csv");
        VirtualFileWrapper wrapper = FileChooserFactory.getInstance()
                .createSaveFileDialog(descriptor, (Component) null)
                .save((VirtualFile) null, "oh-my-token-usage.csv");
        if (wrapper == null) {
            return;
        }
        Path path = wrapper.getFile().toPath();
        List<String> lines = new ArrayList<>();
        lines.add("date,input,output,cache_write,cache_read");
        for (TokenStats.DailyRow r : stats.exportDailyRows()) {
            lines.add(String.format(Locale.ROOT, "%s,%d,%d,%d,%d",
                    r.date(), r.input(), r.output(), r.cacheWrite(), r.cacheRead()));
        }
        try {
            Files.write(path, lines, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            new Notification(TokenStatsWidget.NOTIFICATION_GROUP,
                    OhMyTokenBundle.message("alert.title"),
                    OhMyTokenBundle.message("export.failed", ex.getMessage()),
                    NotificationType.ERROR).notify(null);
            return;
        }
        Notification n = new Notification(TokenStatsWidget.NOTIFICATION_GROUP,
                OhMyTokenBundle.message("alert.title"),
                OhMyTokenBundle.message("export.done", path.toString()),
                NotificationType.INFORMATION);
        n.addAction(NotificationAction.createSimple(
                OhMyTokenBundle.message("export.reveal"), () -> RevealFileAction.openFile(path)));
        n.notify(null);
    }

    @Override
    protected javax.swing.Action @Nullable [] createActions() {
        return new javax.swing.Action[]{getOKAction()};
    }

    /** 堆叠柱状图绘制组件，支持悬停查看明细 */
    private static final class ChartPanel extends JComponent {

        private static final JBColor[] CATEGORY_COLORS = {
                new JBColor(new Color(0x3574F0), new Color(0x548AF7)),   // input
                new JBColor(new Color(0x59A869), new Color(0x499C54)),   // output
                new JBColor(new Color(0xE0885B), new Color(0xD98A4F)),   // cache write
                new JBColor(new Color(0x9A6EC1), new Color(0xA571C9))    // cache read
        };
        private static final JBColor GRID_COLOR = new JBColor(new Color(0xE0E0E0), new Color(0x3C3F41));
        private static final JBColor MA_COLOR = new JBColor(new Color(0xC75450), new Color(0xDB5C5C));

        private static final int LEFT = 44, RIGHT = 8, TOP = 24, BOTTOM = 22;

        private String title;
        private long[][] values;
        private final String[] labels;
        private final boolean movingAverage;

        ChartPanel(String title, long[][] values, String[] labels, boolean movingAverage) {
            this.title = title;
            this.values = values;
            this.labels = labels;
            this.movingAverage = movingAverage;
            ToolTipManager.sharedInstance().registerComponent(this);
        }

        /** 悬停时按 x 坐标定位柱子，ToolTipManager 每轮都会调这个方法取最新文本 */
        @Override
        public @Nullable String getToolTipText(MouseEvent event) {
            return hoverText(event.getX());
        }

        void setData(long[][] values) {
            this.values = values;
            repaint();
        }

        void setTitle(String title) {
            this.title = title;
        }

        private String hoverText(int x) {
            int plotW = getWidth() - LEFT - RIGHT;
            if (plotW <= 0 || values.length == 0) {
                return null;
            }
            int i = (int) ((x - LEFT) / (plotW / (double) values.length));
            if (i < 0 || i >= values.length) {
                return null;
            }
            String[] cats = {
                    OhMyTokenBundle.message("chart.cat.input"),
                    OhMyTokenBundle.message("chart.cat.output"),
                    OhMyTokenBundle.message("chart.cat.cache.write"),
                    OhMyTokenBundle.message("chart.cat.cache.read")
            };
            StringBuilder sb = new StringBuilder(labels[i]);
            long sum = 0;
            for (int c = 0; c < 4; c++) {
                if (values[i].length > c && values[i][c] > 0) {
                    sb.append(" · ").append(cats[c]).append(' ').append(formatShort(values[i][c]));
                    sum += values[i][c];
                }
            }
            sb.append(" · ").append(OhMyTokenBundle.message("chart.hover.total"))
                    .append(' ').append(formatShort(sum));
            return sb.toString();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int w = getWidth(), h = getHeight();
                int plotW = w - LEFT - RIGHT, plotH = h - TOP - BOTTOM;
                int base = h - BOTTOM;

                // 标题 + 图例
                g2.setColor(getForeground());
                g2.drawString(title, LEFT, 16);
                drawLegend(g2);

                int n = values.length;
                if (n == 0) {
                    return;
                }
                long[] totals = new long[n];
                long max = 1;
                for (int i = 0; i < n; i++) {
                    for (long v : values[i]) {
                        totals[i] += v;
                    }
                    max = Math.max(max, totals[i]);
                }
                // 纵轴刻度取整到好看的数量级
                max = niceCeil(max);

                // 网格线 + 纵轴标签（0、50%、100%）
                FontMetrics fm = g2.getFontMetrics();
                for (int i = 0; i <= 2; i++) {
                    int y = base - plotH * i / 2;
                    g2.setColor(GRID_COLOR);
                    g2.setStroke(new BasicStroke(1f));
                    g2.drawLine(LEFT, y, w - RIGHT, y);
                    String s = formatShort(max * i / 2);
                    g2.setColor(getForeground());
                    g2.drawString(s, LEFT - 4 - fm.stringWidth(s), y + fm.getAscent() / 2);
                }

                // 堆叠柱子
                double slot = (double) plotW / n;
                int bw = Math.max(1, (int) (slot * 0.7));
                for (int i = 0; i < n; i++) {
                    int x = LEFT + (int) (i * slot + (slot - bw) / 2);
                    int y = base;
                    for (int c = 0; c < values[i].length && c < CATEGORY_COLORS.length; c++) {
                        long v = values[i][c];
                        if (v <= 0) {
                            continue;
                        }
                        int bh = (int) (plotH * ((double) v / max));
                        if (bh == 0) {
                            bh = 1;
                        }
                        g2.setColor(CATEGORY_COLORS[c]);
                        g2.fillRect(x, y - bh, bw, bh);
                        y -= bh;
                    }
                }

                // 7 天移动平均线（按日图）
                if (movingAverage) {
                    g2.setColor(MA_COLOR);
                    g2.setStroke(new BasicStroke(1.5f));
                    int prevX = -1, prevY = -1;
                    for (int i = 0; i < n; i++) {
                        double sum = 0;
                        int from = Math.max(0, i - 6);
                        for (int j = from; j <= i; j++) {
                            sum += totals[j];
                        }
                        double avg = sum / (i - from + 1);
                        int x = LEFT + (int) (i * slot + slot / 2);
                        int y = base - (int) (plotH * (avg / max));
                        if (prevX >= 0) {
                            g2.drawLine(prevX, prevY, x, y);
                        }
                        prevX = x;
                        prevY = y;
                    }
                }

                // 横轴标签，最多 8 个
                g2.setColor(getForeground());
                g2.setStroke(new BasicStroke(1f));
                int step = Math.max(1, (int) Math.ceil(n / 8.0));
                for (int i = 0; i < n; i += step) {
                    String s = labels[i];
                    int x = LEFT + (int) (i * slot + slot / 2) - fm.stringWidth(s) / 2;
                    g2.drawString(s, x, h - 6);
                }
            } finally {
                g2.dispose();
            }
        }

        private void drawLegend(Graphics2D g2) {
            String[] cats = {
                    OhMyTokenBundle.message("chart.cat.input"),
                    OhMyTokenBundle.message("chart.cat.output"),
                    OhMyTokenBundle.message("chart.cat.cache.write"),
                    OhMyTokenBundle.message("chart.cat.cache.read")
            };
            FontMetrics fm = g2.getFontMetrics();
            int x = LEFT + fm.stringWidth(title) + 24;
            int y = 16;
            for (int c = 0; c < cats.length; c++) {
                g2.setColor(CATEGORY_COLORS[c]);
                g2.fillRect(x, y - 8, 8, 8);
                g2.setColor(getForeground());
                g2.drawString(cats[c], x + 12, y);
                x += 12 + fm.stringWidth(cats[c]) + 16;
            }
        }

        /** 向上取整到 1/2/2.5/5 × 10^k 的刻度 */
        private static long niceCeil(long v) {
            double mag = Math.pow(10, Math.floor(Math.log10(v)));
            for (double m : new double[]{1, 2, 2.5, 5, 10}) {
                if (v <= m * mag) {
                    return (long) (m * mag);
                }
            }
            return (long) (10 * mag);
        }

        private static String formatShort(long n) {
            if (n >= 1_000_000) {
                return String.format("%.1fM", n / 1e6);
            }
            if (n >= 1_000) {
                return String.format("%.0fk", n / 1e3);
            }
            return Long.toString(n);
        }
    }
}
