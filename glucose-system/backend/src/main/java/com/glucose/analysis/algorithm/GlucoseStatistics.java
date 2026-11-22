package com.glucose.analysis.algorithm;

import com.glucose.common.enums.Codes;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * 分来源指标纯计算(§7.1–7.3):无数据库/网络依赖,输入血糖点与目标配置,输出指标与不足原因。
 * - SMBG:均值/极值/SD/CV + 分类型目标的测量达标率;不输出 TIR(SMBG_NOT_TIME_BASED)。
 * - CGM:互斥三分类 TIR/TAR/TBR,仅当完整日 ≥14 且覆盖率 ≥70%;当日不计入长期窗口。
 * - 中途不舍入;恒定序列 SD=0;GMI 仅合格 CGM 展示，不代替实测 HbA1c。
 */
public final class GlucoseStatistics {

    private GlucoseStatistics() {
    }

    public record Point(LocalDateTime time, BigDecimal value, Integer measureType) {
    }

    public record TargetRule(int measureType, BigDecimal low, BigDecimal high) {
    }

    public record Summary(
            String dataSource, String dataKind, LocalDate startDate, LocalDate endDate,
            int pointCount,
            // SMBG 达标率口径
            Integer eligiblePointCount, Integer unconfiguredTargetCount, Integer inTargetCount,
            Double measurementInRangeRate,
            // CGM 覆盖口径
            Integer expectedPointCount, Double dataAdequacy, Long validMonitoringMinutes,
            // 目标
            BigDecimal targetLow, BigDecimal targetHigh,
            // 基础统计
            Double avgGlucose, Double minGlucose, LocalDateTime minAt, Double maxGlucose, LocalDateTime maxAt,
            Double sd, Double cv,
            Double tir, Double tar, Double tbr,
            Double gmi,
            String metricStatus, List<String> reasonCodes,
            long dataRevision, long targetRevision, String algorithmVersion) {
    }

    public static Summary summarize(String dataSource, String dataKind,
                                    List<Point> points, LocalDate startDate, LocalDate endDate,
                                    BigDecimal cgmLow, BigDecimal cgmHigh,
                                    List<TargetRule> smbgRules,
                                    long dataRevision, long targetRevision) {
        List<String> reasons = new ArrayList<>();
        if (points.isEmpty()) {
            return new Summary(dataSource, dataKind, startDate, endDate, 0,
                    null, null, null, null, null, null, null,
                    cgmLow, cgmHigh, null, null, null, null, null, null, null,
                    null, null, null, null, Codes.METRIC_NO_DATA,
                    List.of(Codes.REASON_INSUFFICIENT_SAMPLES), dataRevision, targetRevision,
                    Codes.ALGORITHM_VERSION);
        }
        int n = points.size();
        double sum = 0;
        double min = Double.MAX_VALUE;
        double max = -Double.MAX_VALUE;
        LocalDateTime minAt = null;
        LocalDateTime maxAt = null;
        for (Point p : points) {
            double v = p.value().doubleValue();
            sum += v;
            if (v < min) {
                min = v;
                minAt = p.time();
            }
            if (v > max) {
                max = v;
                maxAt = p.time();
            }
        }
        double mean = sum / n;
        Double sd = null;
        Double cv = null;
        if (n >= 2) {
            double sq = 0;
            for (Point p : points) {
                double v = p.value().doubleValue();
                sq += (v - mean) * (v - mean);
            }
            sd = Math.sqrt(sq / n);
            cv = mean == 0 ? null : sd / mean * 100;
        } else {
            reasons.add(Codes.REASON_INSUFFICIENT_SAMPLES);
        }

        boolean isCgm = Codes.SOURCE_CGM.equals(dataSource);
        Double tir = null;
        Double tar = null;
        Double tbr = null;
        Double gmi = null;
        Integer expected = null;
        Double adequacy = null;
        Long validMinutes = null;
        Integer eligible = null;
        Integer unconfigured = null;
        Integer inTarget = null;
        Double inRangeRate = null;
        String metricStatus;

        if (isCgm) {
            metricStatus = Codes.METRIC_DESCRIPTIVE_ONLY;
            long fullDays = ChronoUnit.DAYS.between(startDate, endDate);
            expected = (int) Math.max(0, fullDays * 288);
            // 有效去重槽位(5 分钟网格)
            Set<LocalDateTime> slots = new HashSet<>();
            for (Point p : points) {
                slots.add(p.time().withSecond(0));
            }
            validMinutes = slots.size() * 5L;
            adequacy = expected > 0 ? (double) slots.size() / expected : null;
            if (fullDays < 14) {
                reasons.add(Codes.REASON_SHORT_WINDOW);
            }
            if (adequacy != null && adequacy < 0.70) {
                reasons.add(Codes.REASON_LOW_COVERAGE);
            }
            if (fullDays >= 14 && adequacy != null && adequacy >= 0.70 && cgmLow != null && cgmHigh != null) {
                long inRange = 0;
                long above = 0;
                long below = 0;
                for (Point p : points) {
                    double v = p.value().doubleValue();
                    if (v < cgmLow.doubleValue()) {
                        below++;
                    } else if (v > cgmHigh.doubleValue()) {
                        above++;
                    } else {
                        inRange++;
                    }
                }
                tir = pct(inRange, points.size());
                tar = pct(above, points.size());
                tbr = pct(below, points.size());
                metricStatus = Codes.METRIC_QUALIFIED;
                gmi = round2(3.31 + 0.4307 * mean);
                reasons.remove(Codes.REASON_SHORT_WINDOW);
                reasons.remove(Codes.REASON_LOW_COVERAGE);
            } else {
                metricStatus = Codes.METRIC_INSUFFICIENT_CGM;
            }
        } else {
            metricStatus = Codes.METRIC_DESCRIPTIVE_ONLY;
            reasons.add(Codes.REASON_SMBG_NOT_TIME_BASED);
            if (smbgRules != null && !smbgRules.isEmpty()) {
                int eligibleCount = 0;
                int unconfiguredCount = 0;
                int inTargetCount = 0;
                for (Point p : points) {
                    TargetRule rule = p.measureType() == null ? null : smbgRules.stream()
                            .filter(r -> r.measureType() == p.measureType()).findFirst().orElse(null);
                    if (rule == null) {
                        unconfiguredCount++;
                        continue;
                    }
                    eligibleCount++;
                    double v = p.value().doubleValue();
                    if (v >= rule.low().doubleValue() && v <= rule.high().doubleValue()) {
                        inTargetCount++;
                    }
                }
                eligible = eligibleCount;
                unconfigured = unconfiguredCount;
                inTarget = inTargetCount;
                inRangeRate = eligibleCount > 0 ? pct(inTargetCount, eligibleCount) : null;
                if (eligibleCount == 0) {
                    reasons.add(Codes.REASON_TARGET_NOT_CONFIGURED);
                }
            } else {
                reasons.add(Codes.REASON_TARGET_NOT_CONFIGURED);
            }
        }

        return new Summary(dataSource, dataKind, startDate, endDate, n,
                eligible, unconfigured, inTarget, inRangeRate,
                expected, adequacy, validMinutes,
                cgmLow, cgmHigh,
                round2(mean), round2(min), minAt, round2(max), maxAt,
                sd == null ? null : round2(sd), cv == null ? null : round2(cv),
                tir, tar, tbr, gmi, metricStatus, reasons,
                dataRevision, targetRevision, Codes.ALGORITHM_VERSION);
    }

    private static double pct(long part, long total) {
        return total == 0 ? 0 : Math.round(part * 1000.0 / total) / 10.0;
    }

    private static Double round2(double v) {
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    /* ================= 趋势展示点(≤2000,保极值,分桶降采样) ================= */

    public record TrendPoint(String time, double value, String dataSource, String dataKind) {
    }

    public record Trend(List<TrendPoint> points, int totalPoints, int displayPoints,
                        List<Gap> gaps, String dataSource, String dataKind) {
    }

    public record Gap(String start, String end) {
    }

    public static Trend trend(String dataSource, String dataKind, List<Point> points, int maxPoints) {
        points = points.stream().sorted(Comparator.comparing(Point::time)).toList();
        List<Gap> gaps = new ArrayList<>();
        if (Codes.SOURCE_CGM.equals(dataSource)) {
            for (int i = 1; i < points.size(); i++) {
                if (java.time.Duration.between(points.get(i - 1).time(), points.get(i).time()).compareTo(java.time.Duration.ofMinutes(15)) > 0) {
                    gaps.add(new Gap(points.get(i - 1).time().format(com.glucose.config.JacksonConfig.DATETIME),
                            points.get(i).time().format(com.glucose.config.JacksonConfig.DATETIME)));
                }
            }
        }
        List<TrendPoint> display;
        if (points.size() <= maxPoints) {
            display = points.stream()
                    .map(p -> new TrendPoint(p.time().format(com.glucose.config.JacksonConfig.DATETIME),
                            p.value().doubleValue(), dataSource, dataKind))
                    .toList();
        } else {
            display = downsamplePreserveExtremes(points, maxPoints);
        }
        return new Trend(display, points.size(), display.size(), gaps, dataSource, dataKind);
    }

    /** 保极值分桶:每桶取最大值点与最小值点(按时间先后输出),不改变总量统计。 */
    private static List<TrendPoint> downsamplePreserveExtremes(List<Point> points, int maxPoints) {
        int bucketCount = Math.max(1, maxPoints / 2);
        int bucketSize = (int) Math.ceil((double) points.size() / bucketCount);
        List<TrendPoint> out = new ArrayList<>(maxPoints);
        for (int start = 0; start < points.size(); start += bucketSize) {
            int end = Math.min(points.size(), start + bucketSize);
            Point min = points.get(start);
            Point max = points.get(start);
            for (int i = start + 1; i < end; i++) {
                Point p = points.get(i);
                if (p.value().doubleValue() < min.value().doubleValue()) {
                    min = p;
                }
                if (p.value().doubleValue() > max.value().doubleValue()) {
                    max = p;
                }
            }
            Point first = min.time().isBefore(max.time()) ? min : max;
            Point second = first == min ? max : min;
            out.add(toTrendPoint(first));
            if (second != first) {
                out.add(toTrendPoint(second));
            }
        }
        return out;
    }

    private static TrendPoint toTrendPoint(Point p) {
        return new TrendPoint(p.time().format(com.glucose.config.JacksonConfig.DATETIME),
                p.value().doubleValue(), "", "");
    }

    /* ================= 每日统计 ================= */

    public record DailyStat(String date, int count, Double avg, Double min, Double max, Double coverage) {
    }

    public static List<DailyStat> daily(List<Point> points, String dataSource,
                                        LocalDate startDate, LocalDate endDate) {
        Map<LocalDate, List<Point>> byDay = new TreeMap<>();
        for (Point p : points) {
            byDay.computeIfAbsent(p.time().toLocalDate(), k -> new ArrayList<>()).add(p);
        }
        List<DailyStat> out = new ArrayList<>();
        for (LocalDate d = startDate; d.isBefore(endDate); d = d.plusDays(1)) {
            List<Point> day = byDay.getOrDefault(d, List.of());
            if (day.isEmpty()) {
                out.add(new DailyStat(d.toString(), 0, null, null, null, null));
                continue;
            }
            double sum = 0;
            double min = Double.MAX_VALUE;
            double max = -Double.MAX_VALUE;
            Set<LocalDateTime> slots = new HashSet<>();
            for (Point p : day) {
                sum += p.value().doubleValue();
                min = Math.min(min, p.value().doubleValue());
                max = Math.max(max, p.value().doubleValue());
                slots.add(p.time().withSecond(0));
            }
            Double coverage = Codes.SOURCE_CGM.equals(dataSource) ? slots.size() / 288.0 * 100 : null;
            out.add(new DailyStat(d.toString(), day.size(), round2(sum / day.size()),
                    round2(min), round2(max), coverage == null ? null : round2(coverage)));
        }
        return out;
    }
}
