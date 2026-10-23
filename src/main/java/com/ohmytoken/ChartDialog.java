package com.ohmytoken;

import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.ui.JBColor;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.RenderingHints;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * 用量趋势图：今日逐小时 + 近 30 天逐日，纯 Swing 手绘柱状图。
 */
final class ChartDialog extends DialogWrapper {

    ChartDialog(TokenStats.Snapshot snap) {
        super((com.intellij.openapi.project.Project) null);
        setTitle(OhMyTokenBundle.message("chart.dialog.title"));
        this.snap = snap;
        init();
    }

    private final TokenStats.Snapshot snap;

    @Override
    protected @Nullable JComponent createCenterPanel() {
        JPanel panel = new JPanel(new GridLayout(2, 1, 0, 12));
        String[] hourLabels = new String[24];
        for (int i = 0; i < 24; i++) {
            hourLabels[i] = String.format("%02d", i);
        }
        panel.add(new ChartPanel(
                OhMyTokenBundle.message("chart.title.hourly"), snap.todayHourly(), hourLabels));

        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("MM-dd");
        LocalDate today = LocalDate.now();
        String[] dayLabels = new String[30];
        for (int i = 0; i < 30; i++) {
            dayLabels[i] = today.minusDays(29 - i).format(fmt);
        }
        panel.add(new ChartPanel(
                OhMyTokenBundle.message("chart.title.daily"), snap.daily30(), dayLabels));

        panel.setPreferredSize(new Dimension(680, 480));
        return panel;
    }

    @Override
    protected javax.swing.Action @Nullable [] createActions() {
        return new javax.swing.Action[]{getOKAction()};
    }

    /** 柱状图绘制组件 */
    private static final class ChartPanel extends JComponent {

        private static final JBColor BAR_COLOR = new JBColor(new Color(0x3574F0), new Color(0x548AF7));
        private static final JBColor GRID_COLOR = new JBColor(new Color(0xE0E0E0), new Color(0x3C3F41));

        private final String title;
        private final long[] values;
        private final String[] labels;

        ChartPanel(String title, long[] values, String[] labels) {
            this.title = title;
            this.values = values;
            this.labels = labels;
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int w = getWidth(), h = getHeight();
                int left = 44, right = 8, top = 24, bottom = 22;
                int plotW = w - left - right, plotH = h - top - bottom;
                int base = h - bottom;

                // 标题
                g2.setColor(getForeground());
                g2.drawString(title, left, 16);

                long max = 1;
                for (long v : values) {
                    max = Math.max(max, v);
                }
                // 纵轴刻度取整到好看的数量级
                max = niceCeil(max);

                // 网格线 + 纵轴标签（0、50%、100%）
                g2.setColor(GRID_COLOR);
                g2.setStroke(new BasicStroke(1f));
                FontMetrics fm = g2.getFontMetrics();
                for (int i = 0; i <= 2; i++) {
                    int y = base - plotH * i / 2;
                    g2.drawLine(left, y, w - right, y);
                    String s = formatShort(max * i / 2);
                    g2.setColor(getForeground());
                    g2.drawString(s, left - 4 - fm.stringWidth(s), y + fm.getAscent() / 2);
                    g2.setColor(GRID_COLOR);
                }

                // 柱子
                int n = values.length;
                double slot = (double) plotW / n;
                int bw = Math.max(1, (int) (slot * 0.7));
                g2.setColor(BAR_COLOR);
                for (int i = 0; i < n; i++) {
                    int bh = (int) (plotH * ((double) values[i] / max));
                    if (values[i] > 0 && bh == 0) {
                        bh = 1;
                    }
                    int x = left + (int) (i * slot + (slot - bw) / 2);
                    g2.fillRect(x, base - bh, bw, bh);
                }

                // 横轴标签，最多 8 个
                g2.setColor(getForeground());
                int step = Math.max(1, (int) Math.ceil(n / 8.0));
                for (int i = 0; i < n; i += step) {
                    String s = labels[i];
                    int x = left + (int) (i * slot + slot / 2) - fm.stringWidth(s) / 2;
                    g2.drawString(s, x, h - 6);
                }
            } finally {
                g2.dispose();
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
