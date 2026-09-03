package com.project.medivice.dto;

/** SCR-REG-002 인식 결과 확인 화면의 한 줄(D-4: 신뢰도를 값과 함께 노출). */
public record OcrRowDto(String key, String value, Double confidence) {
}
