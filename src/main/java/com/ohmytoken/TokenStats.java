package com.ohmytoken;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * token 统计：
 * - 60s 滑动窗口算输出速率（tok/s）
 * - 5h 滚动窗口算限额用量（全口径 token）
 * - 按日 / 按日+模型 / 按日+项目聚合算区间总量
 * - 逐小时桶（画趋势图）
 * - 最新一条 usage 的 input+cache ≈ 当前会话上下文占用
 */
final class TokenStats {

    private static final long RATE_WINDOW_MILLIS = 60_000;
    private static final long FIVE_HOUR_MILLIS = 5 * 3600_000L;
    /** 打满时间预测基于最近 10 分钟的平均速率 */
    private static final long PROJECTION_WINDOW_MILLIS = 10 * 60_000L;
    private static final int KEEP_DAYS = 31;

    private static final class RateEvent {
        final long at;
        final long output;

        RateEvent(long at, long output) {
            this.at = at;
            this.output = output;
        }
    }

    private static final class TotalEvent {
        final long at;
        final long total;

        TotalEvent(long at, long total) {
            this.at = at;
            this.total = total;
        }
    }

    private final Deque<RateEvent> rateWindow = new ArrayDeque<>();
    private final Deque<TotalEvent> fiveHourWindow = new ArrayDeque<>();
    /** date -> [input, output, cache] */
    private final Map<LocalDate, long[]> daily = new HashMap<>();
    /** date -> model -> [input, output, cache] */
    private final Map<LocalDate, Map<String, long[]>> dailyByModel = new HashMap<>();
    /** date -> project -> [input, output, cache] */
    private final Map<LocalDate, Map<String, long[]>> dailyByProject = new HashMap<>();
    /** date -> 24 小时桶（全口径 total） */
    private final Map<LocalDate, long[]> hourly = new HashMap<>();
    private long allTimeTotal;
    private long lastEventMillis;
    /** 最近一条带时间戳事件的 input+cache，即该会话当前上下文占用 */
    private long lastContextTokens;

    /**
     * @param timestamped 事件是否带合法时间戳；无时间戳的只计入全部总量，
     *                    不进窗口和按日桶（避免启动回填时速率虚高）
     */
    synchronized void add(long input, long output, long cache, String model, String project,
                          Instant at, boolean timestamped) {
        long total = input + output + cache;
        allTimeTotal += total;
        if (!timestamped) {
            return;
        }
        long millis = at.toEpochMilli();
        if (millis >= lastEventMillis) {
            lastContextTokens = input + cache;
        }
        lastEventMillis = Math.max(lastEventMillis, millis);

        var zoned = at.atZone(ZoneId.systemDefault());
        LocalDate day = zoned.toLocalDate();

        accumulate(daily.computeIfAbsent(day, d -> new long[3]), input, output, cache);
        accumulate(dailyByModel.computeIfAbsent(day, d -> new HashMap<>())
                .computeIfAbsent(blank(model), k -> new long[3]), input, output, cache);
        accumulate(dailyByProject.computeIfAbsent(day, d -> new HashMap<>())
                .computeIfAbsent(blank(project), k -> new long[3]), input, output, cache);
        hourly.computeIfAbsent(day, d -> new long[24])[zoned.getHour()] += total;

        if (output > 0) {
            rateWindow.addLast(new RateEvent(millis, output));
        }
        if (total > 0) {
            fiveHourWindow.addLast(new TotalEvent(millis, total));
        }
        prune(System.currentTimeMillis());
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? "unknown" : s;
    }

    private static void accumulate(long[] b, long input, long output, long cache) {
        b[0] += input;
        b[1] += output;
        b[2] += cache;
    }

    /**
     * @param customDate 非空时忽略 range，只聚合这一天
     */
    synchronized Snapshot snapshot(DisplayRange range, LocalDate customDate) {
        long now = System.currentTimeMillis();
        prune(now);

        long windowOutput = 0;
        for (RateEvent e : rateWindow) {
            windowOutput += e.output;
        }
        double outputPerSec = windowOutput / (RATE_WINDOW_MILLIS / 1000.0);

        long fiveHourUsed = 0, recentTotal = 0;
        long recentCutoff = now - PROJECTION_WINDOW_MILLIS;
        for (TotalEvent e : fiveHourWindow) {
            fiveHourUsed += e.total;
            if (e.at >= recentCutoff) {
                recentTotal += e.total;
            }
        }
        double recentTotalPerMin = recentTotal / (PROJECTION_WINDOW_MILLIS / 60_000.0);

        LocalDate today = LocalDate.now();
        long rangeInput = 0, rangeOutput = 0, rangeCache = 0;
        Map<String, long[]> models = new HashMap<>();
        Map<String, long[]> projects = new HashMap<>();
        if (customDate == null && range == DisplayRange.ALL) {
            for (long[] b : daily.values()) {
                rangeInput += b[0];
                rangeOutput += b[1];
                rangeCache += b[2];
            }
            for (Map<String, long[]> m : dailyByModel.values()) {
                mergeInto(models, m);
            }
            for (Map<String, long[]> m : dailyByProject.values()) {
                mergeInto(projects, m);
            }
        } else {
            int days = customDate != null ? 1 : range.days();
            LocalDate start = customDate != null ? customDate : today;
            for (int i = 0; i < days; i++) {
                LocalDate d = start.minusDays(i);
                long[] b = daily.get(d);
                if (b != null) {
                    rangeInput += b[0];
                    rangeOutput += b[1];
                    rangeCache += b[2];
                }
                Map<String, long[]> m = dailyByModel.get(d);
                if (m != null) {
                    mergeInto(models, m);
                }
                Map<String, long[]> p = dailyByProject.get(d);
                if (p != null) {
                    mergeInto(projects, p);
                }
            }
        }

        // 趋势图数据：今日逐小时 + 近 30 天逐日（index 29 = 今天）
        long[] todayHourly = hourly.getOrDefault(today, new long[24]).clone();
        long[] daily30 = new long[30];
        for (int i = 0; i < 30; i++) {
            long[] b = daily.get(today.minusDays(29 - i));
            if (b != null) {
                daily30[i] = b[0] + b[1] + b[2];
            }
        }

        return new Snapshot(
                outputPerSec,
                rangeInput,
                rangeOutput,
                rangeCache,
                customDate == null && range == DisplayRange.ALL
                        ? allTimeTotal : rangeInput + rangeOutput + rangeCache,
                fiveHourUsed,
                recentTotalPerMin,
                sortByTotalDesc(models),
                sortByTotalDesc(projects),
                todayHourly,
                daily30,
                lastContextTokens,
                lastEventMillis
        );
    }

    private static void mergeInto(Map<String, long[]> into, Map<String, long[]> from) {
        from.forEach((key, b) -> accumulate(
                into.computeIfAbsent(key, k -> new long[3]), b[0], b[1], b[2]));
    }

    private static Map<String, long[]> sortByTotalDesc(Map<String, long[]> map) {
        Map<String, long[]> sorted = new LinkedHashMap<>();
        map.entrySet().stream()
                .sorted((a, b) -> Long.compare(total(b.getValue()), total(a.getValue())))
                .forEach(e -> sorted.put(e.getKey(), e.getValue()));
        return sorted;
    }

    private static long total(long[] b) {
        return b[0] + b[1] + b[2];
    }

    private void prune(long nowMillis) {
        long rateCutoff = nowMillis - RATE_WINDOW_MILLIS;
        while (!rateWindow.isEmpty() && rateWindow.peekFirst().at < rateCutoff) {
            rateWindow.pollFirst();
        }
        long fiveHourCutoff = nowMillis - FIVE_HOUR_MILLIS;
        while (!fiveHourWindow.isEmpty() && fiveHourWindow.peekFirst().at < fiveHourCutoff) {
            fiveHourWindow.pollFirst();
        }
        LocalDate oldest = LocalDate.now().minusDays(KEEP_DAYS);
        daily.keySet().removeIf(d -> d.isBefore(oldest));
        dailyByModel.keySet().removeIf(d -> d.isBefore(oldest));
        dailyByProject.keySet().removeIf(d -> d.isBefore(oldest));
        hourly.keySet().removeIf(d -> d.isBefore(oldest));
    }

    /** 某一时刻的统计快照（已按所选范围聚合） */
    record Snapshot(
            double outputPerSec,
            long rangeInput,
            long rangeOutput,
            long rangeCache,
            long rangeTotal,
            long fiveHourUsed,
            double recentTotalPerMin,
            Map<String, long[]> models,
            Map<String, long[]> projects,
            long[] todayHourly,
            long[] daily30,
            long contextTokens,
            long lastEventMillis
    ) {
        boolean active() {
            return lastEventMillis > 0
                    && System.currentTimeMillis() - lastEventMillis < 30_000;
        }
    }
}
