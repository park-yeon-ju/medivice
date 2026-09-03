package com.project.medivice.ai;

import java.math.BigDecimal;
import java.util.List;

/**
 * Sprint 3 DoD: "AI 호출이 AiClient 인터페이스 뒤에 있어 설정값 변경만으로 구현체를 교체할 수
 * 있다". 지금은 MockAiClient와 ClaudeAiClient 둘이고, medivice.ai.provider 값만 바뀐다
 * (호출부인 ReportService·OcrService는 그대로 둔다).
 */
public interface AiClient {

    /**
     * UC29 보고서용 요약 문장을 만든다. 어떤 문장을 만들지는 구현체 책임이고, 호출부는
     * 숫자 근거(medicationCount 등)만 건넨다 — "AI는 이미 정해진 사실만 풀어쓴다"는 원칙을
     * 인터페이스 경계에서부터 강제한다.
     */
    String summarizeReport(ReportContext context);

    record ReportContext(
            int medicationCount,
            int warnCount,
            int critCount,
            int symptomCount,
            String language) {
    }

    /**
     * UC8~10(EXT-1): 처방전·약봉투·제품 라벨 사진 → 구조화된 등록 정보 목록. 한국 약봉투는
     * 보통 서로 다른 약 여러 개를 한 봉투에 같이 담아 주므로, 사진 한 장에서 그 전부를
     * 추출한다(목록 길이 1이면 약이 하나뿐이었다는 뜻). 색·판정은 이 결과로 직접 내려지지
     * 않는다 — 사용자가 확인 화면(SCR-REG-002)에서 값을 고친 뒤 UC13과 같은
     * POST /api/medications 경로로 하나씩 등록해야 실제로 저장된다(D-4: 확인 전 저장 금지).
     */
    List<OcrExtractionResult> extractMedicationInfo(byte[] imageBytes, String mimeType);

    record OcrExtractionResult(
            String hospitalName,
            Double hospitalConfidence,
            String department,
            String productName,
            Double productNameConfidence,
            List<ExtractedIngredient> ingredients,
            Double ingredientsConfidence,
            BigDecimal dosePerIntake,
            String doseUnit,
            Integer timesPerDay,
            Double doseConfidence,
            String durationNote,
            Double durationConfidence,
            String suggestedType,
            String note) {

        public record ExtractedIngredient(String name, String englishName, BigDecimal amount, String unit) {
        }
    }

    /**
     * UC13/8~12 등록 직후, "이 약이 뭘 위한 약인지" + "복용 중 흔히 느낄 수 있는 것" 1~2문장으로
     * 설명한다. 진단이 아니라 기계적 설명이라는 원칙은 summarizeReport와 같다 — 제품명·성분명,
     * 그리고 있으면 식약처 e약은요 효능·효과(efficacy)·부작용(sideEffect) 원문만 근거로 쓰고,
     * 그 밖의 사실을 지어내지 않는다. 둘 다 없으면(제품이 DB에 없거나 수기 등록) 성분명만으로
     * 일반적인 설명을 만들고, 부작용은 언급하지 않는다(모르는 부작용을 지어내는 게 가장 위험하다).
     */
    String explainMedication(MedicationExplainContext context);

    record MedicationExplainContext(
            String productName,
            List<String> ingredientNames,
            String efficacy,
            String sideEffect) {
    }
}
