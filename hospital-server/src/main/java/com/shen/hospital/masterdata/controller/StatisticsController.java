package com.shen.hospital.masterdata.controller;

import com.shen.hospital.common.*;
import com.shen.hospital.masterdata.mapper.StatisticsMapper;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/statistics")
public class StatisticsController {
    private final StatisticsMapper statistics;
    private final BusinessTime time;
    public StatisticsController(StatisticsMapper statistics,BusinessTime time){this.statistics=statistics;this.time=time;}
    @GetMapping
    public ApiResponse<Map<String,Object>> get(
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate endDate) {
        LocalDate start=startDate==null?time.today().minusDays(6):startDate,end=endDate==null?time.today():endDate;
        if(end.isBefore(start)||ChronoUnit.DAYS.between(start,end)>92)throw BusinessException.invalid("统计范围须为1到93天");
        return ApiResponse.ok(Map.of("startDate",start,"endDate",end,"registrations",statistics.registrations(start,end),
                "agents",statistics.agents(start,end),"departments",statistics.departments(start,end),"daily",statistics.daily(start,end)));
    }
}
