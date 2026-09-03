package com.project.medivice.dto;

import java.math.BigDecimal;
import java.util.List;

public record MedicationDto(
        String id,
        String type,
        String name,
        List<IngredientDto> ingredients,
        String ingredientNote,
        BigDecimal dose,
        String doseUnit,
        Integer timesPerDay,
        String timing,
        String hospital,
        String department,
        String reason,
        String startDate,
        String duration,
        boolean analysisEligible,
        String aiExplanation) {
}
