package com.project.medivice.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * SCR-REG-002 응답. 필드명을 MedicationCreateRequest와 최대한 맞춰서, 사용자가 확인 후
 * "네 · 이대로 등록"을 누르면 프론트가 거의 그대로 옮겨 담아 POST /api/medications를 호출할 수 있게 한다.
 */
public record OcrResultDto(
        String type,
        String name,
        List<IngredientDto> ingredients,
        BigDecimal dose,
        String doseUnit,
        Integer timesPerDay,
        String hospital,
        String department,
        String duration,
        List<OcrRowDto> rows,
        String note) {
}
