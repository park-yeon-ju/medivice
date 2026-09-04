package com.project.medivice.dto;

import java.util.List;

public record DashboardResponse(
        UserDto user,
        List<MedicationDto> medications,
        List<SymptomDto> symptoms,
        MedilightDto medilight) {
}
