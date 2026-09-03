package com.project.medivice.service;

import com.project.medivice.ai.AiClient;
import com.project.medivice.dto.IngredientDto;
import com.project.medivice.dto.OcrResultDto;
import com.project.medivice.dto.OcrRowDto;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * UC8~10(EXT-1). 이미지를 AiClient에 넘기고, 사진 한 장에서 나온 약 목록을 SCR-REG-002 확인
 * 화면이 그릴 수 있는 모양(rows: key·value·confidence)으로 바꾼다. 여기서 아무것도 저장하지
 * 않는다 — 사용자가 확인 화면에서 항목별로 "등록"을 눌러야 UC13과 같은 등록 API가 실행된다
 * (D-4 확인 전 저장 금지).
 */
@Service
public class OcrService {

    private static final long MAX_FILE_BYTES = 10L * 1024 * 1024;

    private final AiClient aiClient;

    public OcrService(AiClient aiClient) {
        this.aiClient = aiClient;
    }

    /** 사진 한 장에서 서로 다른 약 여러 개가 나올 수 있다(약봉투) — 결과는 항목마다 하나씩 목록으로 온다. */
    public List<OcrResultDto> extract(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("이미지 파일이 비어 있습니다.");
        }
        if (file.getSize() > MAX_FILE_BYTES) {
            throw new IllegalArgumentException("이미지 용량이 10MB를 초과합니다.");
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new IllegalStateException("이미지를 읽지 못했습니다.", e);
        }
        List<AiClient.OcrExtractionResult> results = aiClient.extractMedicationInfo(bytes, file.getContentType());
        if (results.isEmpty()) {
            throw new IllegalStateException("사진에서 약 정보를 읽지 못했습니다. 더 선명한 사진으로 다시 시도해주세요.");
        }
        return results.stream().map(this::toDto).toList();
    }

    private OcrResultDto toDto(AiClient.OcrExtractionResult r) {
        List<IngredientDto> ingredients = r.ingredients() == null ? List.of() : r.ingredients().stream()
                .map(i -> new IngredientDto(i.name(), i.englishName(), i.amount(), i.unit()))
                .toList();

        List<OcrRowDto> rows = new ArrayList<>();
        if (r.hospitalName() != null || r.department() != null) {
            String value = Stream.of(r.hospitalName(), r.department())
                    .filter(Objects::nonNull)
                    .collect(Collectors.joining(" · "));
            rows.add(new OcrRowDto("병원 · 진료과", value, r.hospitalConfidence()));
        }
        if (r.productName() != null) {
            rows.add(new OcrRowDto("제품명", r.productName(), r.productNameConfidence()));
        }
        if (!ingredients.isEmpty()) {
            String value = ingredients.stream()
                    .map(i -> i.name() + " " + formatAmount(i.amount()) + i.unit())
                    .collect(Collectors.joining(" · "));
            rows.add(new OcrRowDto("성분", value, r.ingredientsConfidence()));
        }
        if (r.dosePerIntake() != null) {
            String value = formatAmount(r.dosePerIntake()) + (r.doseUnit() != null ? r.doseUnit() : "");
            rows.add(new OcrRowDto("1회 투여량", value, r.doseConfidence()));
        }
        if (r.timesPerDay() != null) {
            rows.add(new OcrRowDto("1일 횟수", r.timesPerDay() + "회", r.doseConfidence()));
        }
        if (r.durationNote() != null) {
            rows.add(new OcrRowDto("복용 기간", r.durationNote(), r.durationConfidence()));
        }

        return new OcrResultDto(
                r.suggestedType(), r.productName(), ingredients,
                r.dosePerIntake(), r.doseUnit(), r.timesPerDay(),
                r.hospitalName(), r.department(), r.durationNote(),
                rows, r.note());
    }

    private static String formatAmount(BigDecimal amount) {
        return amount == null ? "" : amount.stripTrailingZeros().toPlainString();
    }
}
