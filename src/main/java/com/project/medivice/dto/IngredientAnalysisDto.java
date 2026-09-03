package com.project.medivice.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * v_active_ingredients + v_overdose 를 성분 하나로 묶은 결과. 프론트의 medilight.totals /
 * medilight.findings 항목과 동일한 모양이다 (findings 는 status != OK 인 것만 걸러낸 부분집합).
 */
public record IngredientAnalysisDto(
        String ingredient,
        String englishName,
        String unit,
        BigDecimal dailyTotal,
        List<IngredientSourceDto> sources,
        String status,
        String reasonCode,
        BigDecimal upperLimit,
        String reference,
        Double ratio) {
}
