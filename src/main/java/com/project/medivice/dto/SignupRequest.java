package com.project.medivice.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * UC1 회원가입. sex는 화면 표기 그대로("여성"/"남성"/"선택 안 함")를 받아 AuthService가
 * users.gender(M/F/NULL)로 옮긴다. birthDate는 "YYYY-MM-DD" 문자열(비워두면 오늘 날짜로 대체).
 */
public record SignupRequest(
        @NotBlank String loginId,
        @NotBlank String password,
        String sex,
        String birthDate) {
}
