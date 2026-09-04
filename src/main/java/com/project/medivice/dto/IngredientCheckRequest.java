package com.project.medivice.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.math.BigDecimal;
import java.util.List;

/**
 * POST /api/ingredients/check 입력. 실제 등록된 사용자 없이, 성분 이름(과 선택적으로 함량)만
 * 넣으면 그 조합이 병용금기·효능군중복·중복/상한초과에 걸리는지 바로 확인할 수 있다.
 * 같은 이름을 두 번 넣으면 "중복 복용(과다복용 가능성)"으로 잡힌다 — 실제 등록 없이도
 * "이 성분 두 개를 같이 먹어도 되나?"를 미리 확인하는 용도.
 */
public record IngredientCheckRequest(@NotEmpty @Valid List<Item> ingredients) {

    public record Item(
            @NotBlank String name,
            BigDecimal amount,
            String unit) {
    }
}
