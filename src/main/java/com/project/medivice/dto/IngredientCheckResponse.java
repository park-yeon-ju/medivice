package com.project.medivice.dto;

import java.util.List;

/**
 * POST /api/ingredients/check 응답. MedilightDto와 같은 어휘(OK/WARN/CRIT)를 쓴다 — 실제
 * 등록된 사용자의 판정과 같은 규칙(DUR 병용금기·효능군중복·성분별 상한)을 그대로 재사용하기 때문이다.
 * 다만 이건 "만약 이 성분들을 같이 먹는다면"을 미리 보는 도구라, 임부금기·연령금기처럼 사용자
 * 개인 조건이 필요한 판정(dur_single_rules)은 포함하지 않는다 — 여기엔 특정 사용자가 없다.
 */
public record IngredientCheckResponse(
        String status,
        String summary,
        List<IngredientAnalysisDto> findings,
        List<ConflictDto> conflicts,
        List<String> unresolvedNames,
        String note) {
}
