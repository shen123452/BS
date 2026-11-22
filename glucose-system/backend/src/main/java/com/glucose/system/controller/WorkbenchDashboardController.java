package com.glucose.system.controller;

import com.glucose.common.api.Result;
import com.glucose.common.enums.Codes;
import com.glucose.security.LoginContext;
import com.glucose.security.LoginUser;
import com.glucose.system.service.WorkbenchDashboardService;
import com.glucose.system.service.AuditService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/dashboard")
@RequiredArgsConstructor
public class WorkbenchDashboardController {

    private final WorkbenchDashboardService dashboardService;
    private final AuditService auditService;

    @GetMapping("/overview")
    @PreAuthorize("hasAnyRole('DOCTOR','ADMIN')")
    public Result<WorkbenchDashboardService.Overview> overview() {
        LoginUser viewer = LoginContext.current();
        if (Codes.ROLE_ADMIN.equals(viewer.role())) {
            auditService.record(viewer.userId(), "admin.view.dashboard", "aggregate", "",
                    "管理员查看工作台聚合概览", null);
        }
        return Result.ok(dashboardService.overview(viewer));
    }
}
