package com.project.medivice.dto;

import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.List;

public record ReportCreateRequest(
        @NotNull LocalDate from,
        @NotNull LocalDate to,
        String language,
        List<String> include) {
}
