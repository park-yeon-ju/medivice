package com.project.medivice.dto;

import java.util.List;

public record UserDto(
        Long id,
        String username,
        String name,
        String sex,
        String birthDate,
        Integer age,
        List<String> conditions,
        List<String> allergies,
        Double height,
        Double weight,
        String adverseHistory) {
}
