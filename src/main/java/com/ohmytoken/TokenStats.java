package com.ohmytoken;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * token 统计：
 * - 60s 滑动窗口算输出速率（tok/s）
 * - 5h / 7d 滚动窗口算限额用量（全口径 token）
 * - 按日 / 按日+模型 / 按日+项目聚合算区间总量
 * - 逐小时桶（画趋势图，按 input/output/cacheWrite/cacheRead 分类）
 * - 最新一条 usage 的 input+cache ≈ 当前会话上下文占用
 */
final class TokenStats {

    private static final long RATE_WINDOW_MILLIS = 60_000;
    private static final long FIVE_HOUR_MILLIS = 5 * 3600_000L;
    private static final long SEVEN_DAY_MILLIS = 7 * 24 * 3600_000L;
    /** 同一分钟内到期的事件算作同一批释放 */
    private static final long RELEASE_BATCH_MILLIS = 60_000;
    /** 打满时间预测基于最近 10 分钟的平均速率 */
    private static final long PROJECTION_WINDOW_MILLIS = 10 * 60_000L;
    private static final int KEEP_DAYS = 31;

    /** 聚合桶：input、output、cacheWrite、cacheRead */
    private static final int BUCKET_SIZE = 4;

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
    private final Deque<TotalEvent> sevenDayWindow = new ArrayDeque<>();
    /** date -> 聚合桶 */
    private final Map<LocalDate, long[]> daily = new HashMap<>();
    /** date -> model -> 聚合桶 */
    private final Map<LocalDate, Map<String, long[]>> dailyByModel = new HashMap<>();
    /** date -> project -> 聚合桶 */
    private final Map<LocalDate, Map<String, long[]>> dailyByProject = new HashMap<>();
    /** date -> 24 小时桶，每小时 [input, output, cacheWrite, cacheRead] */
    private final Map<LocalDate, long[][]> hourly = new HashMap<>();
    private long allTimeTotal;
    private long lastEventMillis;
    /** 最近一条带时间戳事件的 input+cache，即该会话当前上下文占用 */
    private long lastContextTokens;
    /** 最近一条事件的模型，用于推断上下文窗口大小 */
    private String lastModel;

    /**
     * @param timestamped 事件是否带合法时间戳；无时间戳的只计入全部总量，
     *                    不进窗口和按日桶（避免启动回填时速率虚高）
     */
    synchronized void add(long input, long output, long cacheWrite, long cacheRead,
                          String model, String project, Instant at, boolean timestamped) {
        long total = input + output + cacheWrite + cacheRead;
        allTimeTotal += total;
        if (!timestamped) {
            return;
        }
        long millis = at.toEpochMilli();
        if (millis >= lastEventMillis) {
            lastContextTokens = input + cacheWrite + cacheRead;
            if (model != null && !model.isBlank()) {
                lastModel = model;
            }
        }
        lastEventMillis = Math.max(lastEventMillis, millis);

        var zoned = at.atZone(ZoneId.systemDefault());
        LocalDate day = zoned.toLocalDate();

        accumulate(daily.computeIfAbsent(day, d -> new long[BUCKET_SIZE]),
                input, output, cacheWrite, cacheRead);
        accumulate(dailyByModel.computeIfAbsent(day, d -> new HashMap<>())
                        .computeIfAbsent(blank(model), k -> new long[BUCKET_SIZE]),
                input, output, cacheWrite, cacheRead);
        accumulate(dailyByProject.computeIfAbsent(day, d -> new HashMap<>())
                        .computeIfAbsent(blank(project), k -> new long[BUCKET_SIZE]),
                input, output, cacheWrite, cacheRead);
        long[] hourBucket = hourly.computeIfAbsent(day, d -> new long[24][4])[zoned.getHour()];
        hourBucket[0] += input;
        hourBucket[1] += output;
        hourBucket[2] += cacheWrite;
        hourBucket[3] += cacheRead;

        if (output > 0) {
            rateWindow.addLast(new RateEvent(millis, output));
        }
        if (total > 0) {
            fiveHourWindow.addLast(new TotalEvent(millis, total));
            sevenDayWindow.addLast(new TotalEvent(millis, total));
        }
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? "unknown" : s;
    }

    private static void accumulate(long[] b, long input, long output,
                                   long cacheWrite, long cacheRead) {
        b[0] += input;
        b[1] += output;
        b[2] += cacheWrite;
        b[3] += cacheRead;
    }

    /**
     * @param from 非空且 {@code to} 也非空时忽略 range，只聚合 [from, to]（含两端）
     */
    synchronized Snapshot snapshot(DisplayRange range, LocalDate from, LocalDate to) {
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
        long sevenDayUsed = 0;
        for (TotalEvent e : sevenDayWindow) {
            sevenDayUsed += e.total;
        }
        double recentTotalPerMin = recentTotal / (PROJECTION_WINDOW_MILLIS / 60_000.0);

        LocalDate today = LocalDate.now();
        boolean custom = from != null && to != null;
        long rangeInput = 0, rangeOutput = 0, rangeCache = 0;
        Map<String, long[]> models = new HashMap<>();
        Map<String, long[]> projects = new HashMap<>();
        if (!custom && range == DisplayRange.ALL) {
            for (long[] b : daily.values()) {
                rangeInput += b[0];
                rangeOutput += b[1];
                rangeCache += b[2] + b[3];
            }
            for (Map<String, long[]> m : dailyByModel.values()) {
                mergeInto(models, m);
            }
            for (Map<String, long[]> m : dailyByProject.values()) {
                mergeInto(projects, m);
            }
        } else {
            LocalDate start;
            int days;
            if (custom) {
                start = from;
                // 只保留 KEEP_DAYS 天数据，超出的部分没有意义
                if (ChronoUnit.DAYS.between(from, to) > KEEP_DAYS) {
                    start = to.minusDays(KEEP_DAYS);
                }
                days = (int) ChronoUnit.DAYS.between(start, to) + 1;
            } else {
                days = range.days();
                start = today.minusDays(days - 1L);
            }
            for (int i = 0; i < days; i++) {
                LocalDate d = start.plusDays(i);
                long[] b = daily.get(d);
                if (b != null) {
                    rangeInput += b[0];
                    rangeOutput += b[1];
                    rangeCache += b[2] + b[3];
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

        // 趋势图数据：今日逐小时（按四类拆分）+ 近 30 天逐日（index 29 = 今天）
        long[][] todayHourly = new long[24][4];
        long[][] src = hourly.get(today);
        if (src != null) {
            for (int i = 0; i < 24; i++) {
                todayHourly[i] = src[i].clone();
            }
        }
        long[][] daily30 = new long[30][4];
        for (int i = 0; i < 30; i++) {
            long[] b = daily.get(today.minusDays(29 - i));
            if (b != null) {
                System.arraycopy(b, 0, daily30[i], 0, 4);
            }
        }
        boolean all = !custom && range == DisplayRange.ALL;
        return new Snapshot(
                outputPerSec,
                rangeInput,
                rangeOutput,
                rangeCache,
                all ? allTimeTotal : rangeInput + rangeOutput + rangeCache,
                fiveHourUsed,
                release(fiveHourWindow, FIVE_HOUR_MILLIS),
                sevenDayUsed,
                release(sevenDayWindow, SEVEN_DAY_MILLIS),
                recentTotalPerMin,
                sortByTotalDesc(models),
                sortByTotalDesc(projects),
                todayHourly,
                daily30,
                lastContextTokens,
                lastModel,
                lastEventMillis
        );
    }

    /** 指定项目近 30 天逐日数据（index 29 = 今天），每天 [input, output, cacheWrite, cacheRead] */
    synchronized long[][] daily30ByProject(String project) {
        long[][] series = new long[30][4];
        LocalDate today = LocalDate.now();
        for (int i = 0; i < 30; i++) {
            Map<String, long[]> m = dailyByProject.get(today.minusDays(29 - i));
            if (m == null) {
                continue;
            }
            long[] b = m.get(project);
            if (b != null) {
                System.arraycopy(b, 0, series[i], 0, 4);
            }
        }
        return series;
    }

    /** CSV 导出行：近 31 天有数据的日期，按时间升序 */
    synchronized List<DailyRow> exportDailyRows() {
        List<DailyRow> rows = new ArrayList<>();
        LocalDate today = LocalDate.now();
        for (int i = KEEP_DAYS; i >= 0; i--) {
            LocalDate d = today.minusDays(i);
            long[] b = daily.get(d);
            if (b != null) {
                rows.add(new DailyRow(d, b[0], b[1], b[2], b[3]));
            }
        }
        return rows;
    }

    private static void mergeInto(Map<String, long[]> into, Map<String, long[]> from) {
        from.forEach((key, b) -> accumulate(
                into.computeIfAbsent(key, k -> new long[BUCKET_SIZE]),
                b[0], b[1], b[2], b[3]));
    }

    private static Map<String, long[]> sortByTotalDesc(Map<String, long[]> map) {
        Map<String, long[]> sorted = new LinkedHashMap<>();
        map.entrySet().stream()
                .sorted((a, b) -> Long.compare(total(b.getValue()), total(a.getValue())))
                .forEach(e -> sorted.put(e.getKey(), e.getValue()));
        return sorted;
    }

    private static long total(long[] b) {
        return b[0] + b[1] + b[2] + b[3];
    }

    /**
     * 窗口内最早一批事件的释放时刻与释放量。跨文件扫描会让队列里的时间戳乱序，
     * 因此这里遍历找最早到期时间，而不是依赖队首。
     */
    private static Release release(Deque<TotalEvent> window, long windowMillis) {
        if (window.isEmpty()) {
            return Release.NONE;
        }
        long earliest = Long.MAX_VALUE;
        for (TotalEvent e : window) {
            earliest = Math.min(earliest, e.at + windowMillis);
        }
        long amount = 0;
        for (TotalEvent e : window) {
            if (e.at + windowMillis <= earliest + RELEASE_BATCH_MILLIS) {
                amount += e.total;
            }
        }
        return new Release(earliest, amount);
    }

    /** 每次取快照时整体裁剪一次（每 2 秒一轮）；不在 add() 里做，避免乱序队列下反复全量扫描 */
    private void prune(long nowMillis) {
        long rateCutoff = nowMillis - RATE_WINDOW_MILLIS;
        rateWindow.removeIf(e -> e.at < rateCutoff);
        long fiveHourCutoff = nowMillis - FIVE_HOUR_MILLIS;
        fiveHourWindow.removeIf(e -> e.at < fiveHourCutoff);
        long sevenDayCutoff = nowMillis - SEVEN_DAY_MILLIS;
        sevenDayWindow.removeIf(e -> e.at < sevenDayCutoff);
        LocalDate oldest = LocalDate.now().minusDays(KEEP_DAYS);
        daily.keySet().removeIf(d -> d.isBefore(oldest));
        dailyByModel.keySet().removeIf(d -> d.isBefore(oldest));
        dailyByProject.keySet().removeIf(d -> d.isBefore(oldest));
        hourly.keySet().removeIf(d -> d.isBefore(oldest));
    }

    /** 窗口内最早一批事件的释放时刻与释放量 */
    record Release(long atMillis, long amount) {
        static final Release NONE = new Release(0, 0);

        boolean present() {
            return atMillis > 0;
        }
    }

    /** CSV 导出一行：某天的聚合 */
    record DailyRow(LocalDate date, long input, long output,
                    long cacheWrite, long cacheRead) {
    }

    /** 某一时刻的统计快照（已按所选范围聚合） */
    record Snapshot(
            double outputPerSec,
            long rangeInput,
            long rangeOutput,
            long rangeCache,
            long rangeTotal,
            long fiveHourUsed,
            Release fiveHourRelease,
            long sevenDayUsed,
            Release sevenDayRelease,
            double recentTotalPerMin,
            /** model -> [input, output, cacheWrite, cacheRead]，按总量降序 */
            Map<String, long[]> models,
            /** project -> [input, output, cacheWrite, cacheRead]，按总量降序 */
            Map<String, long[]> projects,
            /** 24 小时 × [input, output, cacheWrite, cacheRead] */
            long[][] todayHourly,
            /** 30 天 × [input, output, cacheWrite, cacheRead]，index 29 = 今天 */
            long[][] daily30,
            long contextTokens,
            /** 最近一条事件的模型名，可能为 null */
            String lastModel,
            long lastEventMillis
    ) {
        boolean active() {
            return lastEventMillis > 0
                    && System.currentTimeMillis() - lastEventMillis < 30_000;
        }
    }
}
