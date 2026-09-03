package com.project.medivice.ai;

import java.math.BigDecimal;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 실제 모델을 부르지 않고, DB에서 이미 계산된 숫자를 문장 틀에 끼워 넣거나(요약) 고정된
 * 표본 값을 돌려준다(OCR). 강의 2일차 요구("AI 서비스는 Mock API로 JSON 반환")를 만족시키고,
 * ANTHROPIC_API_KEY가 없는 환경에서도 화면 시연이 끊기지 않게 한다.
 */
@Component
@ConditionalOnProperty(prefix = "medivice.ai", name = "provider", havingValue = "mock", matchIfMissing = true)
public class MockAiClient implements AiClient {

    @Override
    public List<OcrExtractionResult> extractMedicationInfo(byte[] imageBytes, String mimeType) {
        // 약봉투에 서로 다른 약 2개가 같이 들어 있는 흔한 경우를 표본으로 보여준다.
        OcrExtractionResult first = new OcrExtractionResult(
                "삼성내과의원", 0.96, "내과",
                "아모잘탄정 5/50mg", 0.94,
                List.of(
                        new OcrExtractionResult.ExtractedIngredient("암로디핀", "Amlodipine", new BigDecimal("5"), "mg"),
                        new OcrExtractionResult.ExtractedIngredient("로사르탄칼륨", "Losartan K", new BigDecimal("50"), "mg")),
                0.91,
                BigDecimal.ONE, "정", 1, 0.62,
                "30일분", 0.88,
                "PRESCRIPTION",
                "medivice.ai.provider=mock — 실제 사진을 읽지 않은 표본 응답입니다.");
        OcrExtractionResult second = new OcrExtractionResult(
                "삼성내과의원", 0.96, "내과",
                "크레스토정 5mg", 0.9,
                List.of(new OcrExtractionResult.ExtractedIngredient("로수바스타틴", "Rosuvastatin", new BigDecimal("5"), "mg")),
                0.9,
                BigDecimal.ONE, "정", 1, 0.85,
                "30일분", 0.88,
                "PRESCRIPTION",
                "medivice.ai.provider=mock — 실제 사진을 읽지 않은 표본 응답입니다.");
        return List.of(first, second);
    }

    @Override
    public String summarizeReport(ReportContext c) {
        boolean english = "en".equalsIgnoreCase(c.language());
        if (c.medicationCount() == 0) {
            return english
                    ? "No active medications were found in this period."
                    : "이 기간에 등록된 복용 항목이 없습니다.";
        }
        if (english) {
            return String.format(
                    "During this period, %d medication(s) were tracked. Rule-based checks flagged %d caution(s) "
                            + "and %d high-priority issue(s), and %d symptom entr(y/ies) were logged. "
                            + "This is a mechanical summary of recorded data, not a diagnosis.",
                    c.medicationCount(), c.warnCount(), c.critCount(), c.symptomCount());
        }
        return String.format(
                "이 기간 동안 %d건의 복용 항목이 기록되었습니다. 규칙 기반 점검에서 주의 %d건, 높은 주의 %d건이 확인되었고, "
                        + "증상 기록은 %d건 남겨졌습니다. 이 요약은 기록된 데이터를 기계적으로 정리한 것이며 진단이 아닙니다.",
                c.medicationCount(), c.warnCount(), c.critCount(), c.symptomCount());
    }
}
