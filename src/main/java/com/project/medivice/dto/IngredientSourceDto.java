package com.project.medivice.dto;

import java.math.BigDecimal;

public record IngredientSourceDto(
        String product,
        BigDecimal amount,
        BigDecimal dose,
        Integer timesPerDay) {
}
