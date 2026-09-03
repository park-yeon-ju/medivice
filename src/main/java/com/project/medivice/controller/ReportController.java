package com.project.medivice.controller;

import com.project.medivice.dto.ReportCreateRequest;
import com.project.medivice.dto.ReportDto;
import com.project.medivice.service.ReportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 프론트 계약: POST /api/reports → 202 (UC28·29, EXT-3 Mock 경계). */
@Tag(name = "진료용 보고서", description = "기간 내 복용 목록·안전 이벤트·증상 기록을 집계하고 AI가 숫자만 문장으로 요약")
@RestController
@RequestMapping("/api/reports")
public class ReportController {

    private final ReportService reportService;

    public ReportController(ReportService reportService) {
        this.reportService = reportService;
    }

    @Operation(summary = "보고서 생성", description = "기간·언어를 받아 그 기간의 복용/주의 건수를 규칙 기반으로 집계하고, AiClient가 그 숫자만으로 요약 문장을 만든다. 비동기 워커 없이 요청 안에서 즉시 COMPLETED까지 처리한다.")
    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ReportDto create(@Valid @RequestBody ReportCreateRequest request) {
        return reportService.create(request);
    }
}
