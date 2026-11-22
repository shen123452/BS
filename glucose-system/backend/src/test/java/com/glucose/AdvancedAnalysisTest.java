package com.glucose;

import com.glucose.analysis.algorithm.*;
import com.glucose.common.enums.Codes;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AdvancedAnalysisTest {
    LocalDate start = LocalDate.of(2026, 9, 1);
    List<GlucoseStatistics.Point> points(int days) {
        var points = new ArrayList<GlucoseStatistics.Point>();
        for (int d=0;d<days;d++) for (int m=0;m<1440;m+=5) points.add(new GlucoseStatistics.Point(start.plusDays(d).atStartOfDay().plusMinutes(m),new BigDecimal("8.0"),null));
        return points;
    }
    GlucoseStatistics.Summary summary(String source, List<GlucoseStatistics.Point> points, int days) {
        return GlucoseStatistics.summarize(source,"REAL",points,start,start.plusDays(days),new BigDecimal("3.9"),BigDecimal.TEN,List.of(),3,2);
    }
    @Test void qualifiedCgmMean8Gmi676And48ConstantBands() {
        var points=points(14); var summary=summary("CGM",points,14);
        assertEquals(6.76,summary.gmi()); assertFalse(summary.reasonCodes().contains("NOT_IMPLEMENTED"));
        var agp=AgpStatistics.calculate(summary,points);
        assertEquals(48,agp.qualifiedSlotCount()); assertEquals(30,agp.resolutionMinutes());
        assertEquals("00:00",agp.slots().get(0).time()); assertEquals("23:30",agp.slots().get(47).time());
        for(var slot:agp.slots()) { assertEquals(14,slot.coveredDays()); assertEquals(84,slot.sampleCount()); assertEquals(8,slot.p5()); assertEquals(8,slot.p50()); assertEquals(8,slot.p95()); }
    }
    @Test void percentileInterpolationAndDistinctDatesGate() {
        var points=new ArrayList<GlucoseStatistics.Point>();
        for(var p:points(14)) {
            int day=(int)java.time.temporal.ChronoUnit.DAYS.between(start,p.time().toLocalDate());
            int minute=p.time().getHour()*60+p.time().getMinute();
            if(minute<30) { if(day<7 && minute==0) points.add(new GlucoseStatistics.Point(p.time(),BigDecimal.valueOf(day+1),null)); }
            else if(minute<60) { if(day<6) points.add(p); }
            else points.add(p);
        }
        Collections.reverse(points); // Sorting must be per slot, independent of arrival order.
        var agp=AgpStatistics.calculate(summary("CGM",points,14),points);
        var s=agp.slots().get(0); assertEquals(7,s.sampleCount()); assertEquals(7,s.coveredDays());
        assertEquals(1.3,s.p5()); assertEquals(2.5,s.p25()); assertEquals(4,s.p50()); assertEquals(5.5,s.p75()); assertEquals(6.7,s.p95());
        var missing=agp.slots().get(1); assertEquals(36,missing.sampleCount()); assertEquals(6,missing.coveredDays()); assertNull(missing.p50());
        assertEquals(47,agp.qualifiedSlotCount()); assertTrue(agp.reasonCodes().contains("INSUFFICIENT_SLOT_DAYS"));
    }
    @Test void shortOrLowCoverageCgmCannotShowGmiOrAgp() {
        for(var pair:List.of(Map.entry(points(7),7),Map.entry(points(9),14))) {
            var s=summary("CGM",pair.getKey(),pair.getValue()); assertNull(s.gmi());
            var agp=AgpStatistics.calculate(s,pair.getKey()); assertEquals(0,agp.qualifiedSlotCount());
            assertTrue(agp.slots().stream().allMatch(slot->slot.p5()==null && slot.p50()==null && slot.p95()==null));
        }
    }
    @Test void smbgNeverProducesLongTermGmiOrAgpEvenWithManySamples() {
        var points=points(14); var s=summary("SMBG",points,14);
        assertNull(s.gmi()); assertNull(s.tir());
        var agp=AgpStatistics.calculate(s,points); assertEquals(0,agp.qualifiedSlotCount());
        assertTrue(agp.slots().stream().allMatch(slot->slot.sampleCount()==0 && slot.p50()==null));
        assertTrue(agp.reasonCodes().contains(Codes.REASON_SMBG_NOT_TIME_BASED));
    }
    @Test void emptyWindowKeepsAll48SlotsEmpty() {
        var agp=AgpStatistics.calculate(summary("CGM",List.of(),14),List.of());
        assertEquals(48,agp.slots().size()); assertEquals(0,agp.qualifiedSlotCount()); assertEquals("NO_DATA",agp.metricStatus());
    }


}
