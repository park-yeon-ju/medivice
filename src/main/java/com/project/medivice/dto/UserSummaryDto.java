package com.project.medivice.dto;

/**
 * GET /api/auth/users 응답 한 건. 회원가입(POST /api/auth/signup)이 실제로 DB에 저장됐는지
 * Swagger에서 바로 확인할 수 있도록 만든 목록 조회용 요약이다 — password_hash 등 민감 정보는
 * 빼고 가입 확인에 필요한 최소 정보(아이디·성별·생년월일·가입 시각)만 내려준다.
 */
public record UserSummaryDto(
        Long id,
        String loginId,
        String gender,
        String birthDate,
        String createdAt) {
}
