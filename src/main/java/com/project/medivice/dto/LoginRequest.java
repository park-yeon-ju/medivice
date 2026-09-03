package com.project.medivice.dto;

import jakarta.validation.constraints.NotBlank;

/** UC2 로그인. Sprint 1은 비밀번호를 검증하지 않는다 — loginId가 존재하는지만 확인한다. */
public record LoginRequest(@NotBlank String loginId, @NotBlank String password) {
}
