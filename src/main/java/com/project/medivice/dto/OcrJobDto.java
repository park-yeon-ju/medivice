package com.project.medivice.dto;

import java.util.List;

/**
 * POST /api/medications/ocr(202) · GET /api/medications/ocr/{jobId} 공통 응답 모양.
 * status가 PENDING·PROCESSING이면 result·error는 null이고, COMPLETED면 result만,
 * FAILED면 error만 채워진다.
 */
public record OcrJobDto(String jobId, String status, List<OcrResultDto> result, String error) {
}
