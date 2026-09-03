package com.project.medivice.controller;

import com.project.medivice.dto.DashboardResponse;
import com.project.medivice.service.DashboardService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 프론트 계약: GET /api/dashboard → 200 (medivice-frontend-handoff.html §7 / mockClient.js API_CONTRACT). */
@RestController
@RequestMapping("/api")
public class DashboardController {

    private final DashboardService dashboardService;

    public DashboardController(DashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    @GetMapping("/dashboard")
    public DashboardResponse dashboard() {
        return dashboardService.build();
    }
}
