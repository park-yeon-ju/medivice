package com.project.medivice.controller;

import com.project.medivice.dto.SymptomCreateRequest;
import com.project.medivice.dto.SymptomDto;
import com.project.medivice.service.SymptomService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 프론트 계약: POST /api/symptoms → 201 (UC20·21, Sprint 2-C 승격 구현). */
@RestController
@RequestMapping("/api/symptoms")
public class SymptomController {

    private final SymptomService symptomService;

    public SymptomController(SymptomService symptomService) {
        this.symptomService = symptomService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SymptomDto create(@Valid @RequestBody SymptomCreateRequest request) {
        return symptomService.create(request);
    }
}
