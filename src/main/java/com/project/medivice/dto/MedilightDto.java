package com.project.medivice.dto;

import java.util.List;

public record MedilightDto(
        String status,
        String summary,
        List<IngredientAnalysisDto> findings,
        List<IngredientAnalysisDto> totals,
        List<ConflictDto> conflicts,
        String ruleVersion,
        String checkedAt,
        int uncoveredCount,
        String noticeMessage,
        List<UncoveredIngredientDto> uncoveredIngredients) {
}
