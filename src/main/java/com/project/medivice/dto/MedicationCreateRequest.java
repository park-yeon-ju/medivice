package com.project.medivice.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.util.List;

/**
 * UC13 수기 등록(SCR-REG-003)과 UC8~12 OCR 등록(SCR-REG-002)이 공유하는 입력값.
 * product_id 매칭 없이 항상 custom_name + 성분 목록을 받는다. 성분을 목록으로 받는 이유는
 * "아모잘탄정 5/50mg"처럼 실제 처방약 상당수가 복합제(성분 2개 이상)이기 때문이다 — OCR이
 * 읽어낸 성분을 하나로 뭉개지 않고 그대로 저장해야 성분별 하루 총량 판정(UC15)이 정확해진다.
 */
public record MedicationCreateRequest(
        @NotBlank @Pattern(regexp = "PRESCRIPTION|OTC|SUPPLEMENT") String type,
        @NotBlank String name,
        @NotEmpty @Valid List<IngredientInput> ingredients,
        @NotNull @Positive BigDecimal dose,
        @NotBlank String doseUnit,
        // medications.chk_med_times(DB CHECK: 1~12)와 반드시 같은 범위여야 한다 — 여기서
        // 막지 않으면 이 값이 그대로 DB까지 가서 DataIntegrityViolationException(원인이
        // 안 보이는 raw SQL 에러)으로 막힌다. "필요 시 복용"은 이 필드가 아니라 별도
        // 경로(추후 as_needed)로 표현해야 한다 — 0은 유효한 "횟수"가 아니다.
        @NotNull @Min(1) @Max(12) Integer timesPerDay,
        @NotBlank String reason,
        String hospital,
        String department,
        String duration,
        String timing) {

    public record IngredientInput(
            @NotBlank String name,
            @NotNull @Positive BigDecimal amount,
            @NotBlank String unit) {
    }
}
