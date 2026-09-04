package com.project.medivice.controller;

import com.project.medivice.dto.DashboardResponse;
import com.project.medivice.service.DashboardService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 프론트 계약: GET /api/dashboard → 200 (medivice-frontend-handoff.html §7 / mockClient.js API_CONTRACT). */
@Tag(name = "대시보드", description = "메인 화면 하나가 필요로 하는 사용자·복용 목록·메디라이트를 한 번에 조회")
@RestController
@RequestMapping("/api")
public class DashboardController {

    private final DashboardService dashboardService;

    public DashboardController(DashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    @Operation(summary = "대시보드 조회", description = "X-Medivice-User 헤더로 식별된 사용자의 프로필·복용 목록·최근 증상·메디라이트 판정을 한 번에 반환한다.")
    @GetMapping("/dashboard")
    public DashboardResponse dashboard() {
        return dashboardService.build();
    }
}
