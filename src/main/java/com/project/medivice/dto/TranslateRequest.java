package com.project.medivice.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotBlank;
import java.util.List;

/** POST /api/translate 요청. texts 순서 그대로 번역해 같은 순서로 돌려준다. */
public record TranslateRequest(
        @NotEmpty List<String> texts,
        @NotBlank String targetLang) {
}
