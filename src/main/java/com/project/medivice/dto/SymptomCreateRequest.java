package com.project.medivice.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import java.time.LocalDate;
import java.util.List;

public record SymptomCreateRequest(
        @NotNull @PastOrPresent LocalDate date,
        String time,
        @NotEmpty List<String> symptoms,
        String note) {
}
