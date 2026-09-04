package com.project.medivice.dto;

/**
 * v_pair_conflict / v_single_conflict / v_effect_dup 을 하나의 모양으로 합친 결과.
 * medilight.status(OK/WARN/CRIT)는 findings(성분-총량 기반)와 이 배열 중 더 심각한 쪽으로
 * 계산되므로, "병용금기라서 CRIT"인데 findings에는 안 잡히는 경우의 근거는 이 배열에만 있다
 * (프론트 MediLightBanner·MedilightView의 CONFLICTS 표가 이 배열을 렌더링한다 — TROUBLESHOOTING.md §36).
 */
public record ConflictDto(
        String type,
        String level,
        String ingredientA,
        String ingredientB,
        String medicationA,
        String medicationB,
        String detail) {
}
