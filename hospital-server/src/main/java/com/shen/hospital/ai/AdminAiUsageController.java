package com.shen.hospital.ai;

import com.shen.hospital.common.ApiResponse;
import com.shen.hospital.common.PageResult;
import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.util.Set;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

/** 管理端 AI 调用观测：用途汇总与明细，指标口径与验收方案一致。 */
@RestController
@RequestMapping("/api/admin/ai-usage")
public class AdminAiUsageController {
    private final AiUsageService usage;

    public AdminAiUsageController(AiUsageService usage) { this.usage = usage; }

    @GetMapping("/summary")
    public ApiResponse<AiUsageService.Summary> summary(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        return ApiResponse.ok(usage.summary(startDate, endDate));
    }

    @GetMapping("/logs")
    public ApiResponse<PageResult<AiUsageService.LogRow>> logs(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(required = false) @Pattern(regexp = "PRECONSULT|AGENT|EMBED_QUERY|EMBED_DOCUMENT") String purpose,
            @RequestParam(required = false) @Pattern(regexp = "RUNNING|SUCCEEDED|FAILED|TIMEOUT|CANCELLED") String status,
            @RequestParam(defaultValue = "1") @Min(1) long page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) long pageSize) {
        return ApiResponse.ok(usage.logs(startDate, endDate, purpose, status, page, pageSize));
    }
}
