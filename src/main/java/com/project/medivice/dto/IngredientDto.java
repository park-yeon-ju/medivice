package com.project.medivice.dto;

import java.math.BigDecimal;

public record IngredientDto(
        String name,
        String englishName,
        BigDecimal amount,
        String unit) {
}
