package com.project.medivice.controller;

import com.project.medivice.dto.TranslateRequest;
import com.project.medivice.dto.TranslateResponse;
import com.project.medivice.service.TranslationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 화면의 "표시 언어" 토글(한국어/English)이 실제로 화면에 남아 있는 사용자 입력 한글 텍스트
 * (증상명·메모, 등록 사유 등 — DUR 마스터에 영문명이 이미 있는 성분명은 대상이 아니다)를
 * DeepL로 옮기는 범용 엔드포인트. ReportService가 보고서 생성 시 쓰는 것과 같은
 * TranslationService를 그대로 재사용한다 — 번역 로직이 두 곳에서 따로 관리되지 않도록.
 */
@Tag(name = "번역", description = "화면 표시 언어 토글용 텍스트 번역(DeepL). 키가 없으면 원문을 그대로 돌려준다")
@RestController
@RequestMapping("/api/translate")
public class TranslateController {

    private final TranslationService translationService;

    public TranslateController(TranslationService translationService) {
        this.translationService = translationService;
    }

    @Operation(
            summary = "텍스트 목록 번역",
            description = "texts를 targetLang(예: EN)으로 번역해 같은 순서로 돌려준다. DEEPL_API_KEY가 없으면 enabled=false와 함께 원문을 그대로 돌려준다.")
    @PostMapping
    public TranslateResponse translate(@Valid @RequestBody TranslateRequest request) {
        return new TranslateResponse(translationService.isEnabled(),
                translationService.translateAll(request.texts(), request.targetLang()));
    }
}
