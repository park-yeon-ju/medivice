package com.project.medivice.controller;

import com.project.medivice.dto.SymptomCreateRequest;
import com.project.medivice.dto.SymptomDto;
import com.project.medivice.service.SymptomService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 프론트 계약: POST /api/symptoms → 201 (UC20·21, Sprint 2-C 승격 구현). */
@Tag(name = "증상 기록", description = "부작용·이상반응 기록. 저장 시점의 활성 복용 목록을 값 복사로 스냅샷한다")
@RestController
@RequestMapping("/api/symptoms")
public class SymptomController {

    private final SymptomService symptomService;

    public SymptomController(SymptomService symptomService) {
        this.symptomService = symptomService;
    }

    @Operation(summary = "증상 기록 저장", description = "날짜·증상·메모를 저장하고, 그 시점에 등록되어 있던 복용 목록을 스냅샷으로 함께 남긴다(약과 증상의 인과관계를 판정하지 않음).")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SymptomDto create(@Valid @RequestBody SymptomCreateRequest request) {
        return symptomService.create(request);
    }
}
