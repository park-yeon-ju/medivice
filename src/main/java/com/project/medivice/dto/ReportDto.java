package com.project.medivice.dto;

import java.util.List;

public record ReportDto(
        String id,
        String jobStatus,
        String generatedAt,
        String from,
        String to,
        String language,
        int medicationCount,
        int warnCount,
        int critCount,
        List<SymptomDto> symptoms,
        String narrative) {
}
