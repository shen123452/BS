package com.shen.hospital.ai;

import com.shen.hospital.common.ApiResponse;

import java.util.Map;

import org.slf4j.MDC;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/ai")
public class AiCapabilityController {
    private final AiCapabilityService capabilities;

    public AiCapabilityController(AiCapabilityService capabilities) {
        this.capabilities = capabilities;
    }

    @GetMapping("/config")
    public ApiResponse<Map<String, Object>> config() {
        return ApiResponse.ok(capabilities.config());
    }

    @PostMapping("/capabilities/test")
    public ApiResponse<AiCapabilityService.Report> probe() {
        return ApiResponse.ok(capabilities.probe(MDC.get("traceId")));
    }
}
