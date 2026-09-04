package com.project.medivice.dto;

import java.util.List;

public record SymptomDto(
        String id,
        String date,
        String time,
        List<String> symptoms,
        String note,
        List<MedicationRefDto> medicationSnapshot) {

    public record MedicationRefDto(String id, String name) {
    }
}
