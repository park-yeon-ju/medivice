package com.project.medivice.controller;

import com.project.medivice.dto.ReportCreateRequest;
import com.project.medivice.dto.ReportDto;
import com.project.medivice.service.ReportService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 프론트 계약: POST /api/reports → 202 (UC28·29, EXT-3 Mock 경계). */
@RestController
@RequestMapping("/api/reports")
public class ReportController {

    private final ReportService reportService;

    public ReportController(ReportService reportService) {
        this.reportService = reportService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ReportDto create(@Valid @RequestBody ReportCreateRequest request) {
        return reportService.create(request);
    }
}
