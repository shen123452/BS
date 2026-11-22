package com.glucose.analysis.algorithm;

import com.glucose.common.enums.Codes;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;

/** 30-minute engineering AGP. Percentile bands describe a distribution, not confidence intervals. */
public final class AgpStatistics {
    private AgpStatistics() {}
    public record Slot(String time, int sampleCount, int coveredDays, Double p5, Double p25,
                       Double p50, Double p75, Double p95) {}
    public record Profile(String dataSource, String dataKind, LocalDate startDate, LocalDate endDate,
                          String metricStatus, List<String> reasonCodes, int resolutionMinutes, int minimumCoveredDays,
                          int qualifiedSlotCount, BigDecimal targetLow, BigDecimal targetHigh, List<Slot> slots,
                          long dataRevision, long targetRevision, String algorithmVersion) {}

    public static Profile calculate(GlucoseStatistics.Summary summary, List<GlucoseStatistics.Point> points) {
        boolean cgm = Codes.SOURCE_CGM.equals(summary.dataSource());
        boolean qualified = cgm && Codes.METRIC_QUALIFIED.equals(summary.metricStatus());
        var buckets = new ArrayList<List<GlucoseStatistics.Point>>();
        for (int i = 0; i < 48; i++) buckets.add(new ArrayList<>());
        if (cgm) for (var p : points) {
            LocalDate day = p.time().toLocalDate();
            if (!day.isBefore(summary.startDate()) && day.isBefore(summary.endDate())) {
                buckets.get(p.time().getHour() * 2 + p.time().getMinute() / 30).add(p);
            }
        }
        var slots = new ArrayList<Slot>();
        int valid = 0;
        for (int i = 0; i < 48; i++) {
            var bucket = buckets.get(i);
            int days = (int) bucket.stream().map(p -> p.time().toLocalDate()).distinct().count();
            List<Double> values = bucket.stream().map(p -> p.value().doubleValue()).sorted().toList();
            boolean enough = qualified && days >= 7;
            if (enough) valid++;
            slots.add(new Slot(String.format(Locale.ROOT,"%02d:%02d",i/2,i%2*30), values.size(), days,
                    enough ? percentile(values,.05) : null, enough ? percentile(values,.25) : null,
                    enough ? percentile(values,.50) : null, enough ? percentile(values,.75) : null,
                    enough ? percentile(values,.95) : null));
        }
        var reasons = new ArrayList<>(summary.reasonCodes());
        if (qualified && valid < 48) reasons.add("INSUFFICIENT_SLOT_DAYS");
        return new Profile(summary.dataSource(),summary.dataKind(),summary.startDate(),summary.endDate(),
                summary.metricStatus(),List.copyOf(reasons),30,7,valid,summary.targetLow(),summary.targetHigh(),
                List.copyOf(slots),summary.dataRevision(),summary.targetRevision(),summary.algorithmVersion());
    }
    // Type-7 linear interpolation: h=(n-1)*p; input is sorted ascending.
    static double percentile(List<Double> sorted, double p) {
        double h = (sorted.size()-1)*p;
        int lo = (int) Math.floor(h), hi = (int) Math.ceil(h);
        double value = sorted.get(lo) + (sorted.get(hi)-sorted.get(lo))*(h-lo);
        return BigDecimal.valueOf(value).setScale(2,RoundingMode.HALF_UP).doubleValue();
    }
}
