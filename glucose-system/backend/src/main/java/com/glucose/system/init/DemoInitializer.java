package com.glucose.system.init;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.glucose.alert.service.AlertService;
import com.glucose.glucose.entity.GlucoseRecord;
import com.glucose.glucose.mapper.GlucoseRecordMapper;
import com.glucose.patient.entity.PatientProfile;
import com.glucose.patient.mapper.PatientProfileMapper;
import com.glucose.system.entity.SysUser;
import com.glucose.system.mapper.SysUserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.HexFormat;

/** Explicit opt-in; creates one DEMO account once and never changes an existing account. */
@Slf4j
@Component
@Profile("dev")
@ConditionalOnProperty(name = "app.demo.enabled", havingValue = "true")
@RequiredArgsConstructor
public class DemoInitializer implements ApplicationRunner {
    private final SysUserMapper users;
    private final PatientProfileMapper profiles;
    private final GlucoseRecordMapper records;
    private final AlertService alerts;

    @Override @Transactional
    public void run(ApplicationArguments args) throws Exception {
        String username = "demo_patient";
        if (users.selectCount(Wrappers.<SysUser>lambdaQuery().eq(SysUser::getUsername, username)) > 0) {
            log.info("demo account exists; skipped generating data"); return;
        }
        String openid = "mock-" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest("mock-openid-salt:glucose-local-demo".getBytes(StandardCharsets.UTF_8))).substring(0, 16);
        if (users.selectCount(Wrappers.<SysUser>lambdaQuery().eq(SysUser::getOpenid, openid)) > 0) {
            log.warn("demo openid exists; skipped generating data"); return;
        }
        var user = new SysUser(); user.setUsername(username); user.setRealName("演示患者（合成数据）");
        user.setOpenid(openid); user.setRole("PATIENT"); user.setDataMode("DEMO"); user.setStatus(1); user.setAuthVersion(0L);
        users.insert(user);
        var profile = new PatientProfile(); profile.setUserId(user.getId()); profile.setCgmTargetLow(new BigDecimal("3.9"));
        profile.setCgmTargetHigh(new BigDecimal("10.0")); profile.setDataRevision(0L); profile.setTargetRevision(1L); profiles.insert(profile);
        profiles.requireLock(user.getId());
        var rules = alerts.loadGlobalRules();
        LocalDate start = LocalDate.now().minusDays(14);
        int count = 0;
        for (int day = 0; day < 14; day++) {
            for (int sample = 0; sample < 288; sample++) {
                if (day == 5 && sample >= 100 && sample < 130) continue; // Deliberate missing interval.
                double value = 6.7 + 1.4 * Math.sin(sample * Math.PI / 72) + .4 * Math.sin(day);
                if (day == 3 && sample >= 30 && sample < 34) value = 3.2;
                if (day == 9 && sample >= 170 && sample < 174) value = 14.8;
                var r = new GlucoseRecord(); r.setPatientId(user.getId()); r.setMeasuredAt(start.plusDays(day).atStartOfDay().plusMinutes(sample * 5L));
                r.setGlucoseMmol(BigDecimal.valueOf(value).setScale(1, RoundingMode.HALF_UP)); r.setDataSource("CGM"); r.setDataKind("DEMO");
                r.setIngestMethod("GENERATOR"); r.setSampleIntervalMinutes(5); r.setNote("本地合成演示数据"); r.setRiskFlag(alerts.classify(r.getGlucoseMmol(), rules).flag().name()); records.insert(r); count++;
            }
            var r = new GlucoseRecord(); r.setPatientId(user.getId()); r.setMeasuredAt(start.plusDays(day).atTime(7, 30));
            r.setGlucoseMmol(new BigDecimal("6.1")); r.setDataSource("SMBG"); r.setDataKind("DEMO"); r.setIngestMethod("GENERATOR"); r.setMeasureType(1);
            r.setNote("本地合成演示数据"); r.setRiskFlag("NONE"); records.insert(r); count++;
        }
        profiles.bumpDataRevision(user.getId());
        alerts.recomputeAfterChange(user.getId(), "CGM", "DEMO", true);
        log.info("created DEMO patient with {} synthetic records", count);
    }
}
