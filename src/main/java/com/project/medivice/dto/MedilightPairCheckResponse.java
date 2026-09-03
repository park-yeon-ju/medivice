package com.project.medivice.dto;

import java.util.List;

/**
 * GET /api/medilight/pair-check 응답. 메디라이트가 색을 정하는 규칙(같은 성분 2개=중복→노랑,
 * 병용금기=빨강, 효능군중복=노랑)을 성분 딱 두 개에 대해서만 바로 보여준다.
 */
public record MedilightPairCheckResponse(
        String status,
        String colorLabel,
        String reasonType,
        String detail,
        String ingredientA,
        String ingredientB,
        List<String> unresolvedNames) {
}
