package com.project.medivice.dto;

import java.util.List;

/**
 * POST /api/translate 응답. enabled=false면 DEEPL_API_KEY가 없어 translations가 원문 그대로라는
 * 뜻이다(번역 API 키가 없다고 화면이 깨지면 안 되므로, 프론트가 이 값을 보고 조용히 원문을 쓴다).
 */
public record TranslateResponse(boolean enabled, List<String> translations) {
}
