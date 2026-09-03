package com.project.medivice.dto;

/**
 * v_pair_conflict / v_single_conflict / v_effect_dup 을 하나의 모양으로 합친 결과.
 * 현재 프론트 SignalLamp/MedilightView는 성분-총량 기반 findings만 렌더링하므로 이 배열은
 * 아직 화면에 그려지지 않지만, medilight.status(OK/WARN/CRIT)는 이 값들까지 반영해 계산된다.
 * 즉 "병용금기라서 CRIT"인데 findings에는 안 잡히는 경우를 설명하는 근거 배열이다.
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
