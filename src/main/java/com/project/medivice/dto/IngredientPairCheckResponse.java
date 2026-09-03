package com.project.medivice.dto;

import java.util.List;

/**
 * GET /api/ingredients/pair-check 응답. 성분 두 칸만 채우면 "중복"(같은 성분)과
 * "충돌"(병용금기·효능군중복) 여부를 한 번에 보여준다 — isDuplicate와 hasConflict를 각각의
 * boolean 필드로 둬서 화면에서 노랑(중복)·빨강(충돌) 배지를 따로 그릴 수 있게 한다.
 */
public record IngredientPairCheckResponse(
        boolean isDuplicate,
        boolean hasConflict,
        String conflictType,
        String colorLabel,
        String detail,
        String ingredientA,
        String ingredientB,
        List<String> unresolvedNames) {
}
