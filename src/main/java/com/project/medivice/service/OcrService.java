package com.project.medivice.service;

import com.project.medivice.ai.AiClient;
import com.project.medivice.dto.IngredientDto;
import com.project.medivice.dto.OcrResultDto;
import com.project.medivice.dto.OcrRowDto;
import com.project.medivice.repository.IngredientRepository.ProductIngredientRow;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
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

    private static final Logger log = LoggerFactory.getLogger(OcrService.class);

    private static final long MAX_FILE_BYTES = 10L * 1024 * 1024;

    /**
     * 휴대폰 사진은 보통 4000px+ 라 OpenAI 쪽에서 처리할 이미지 타일 수가 과도하게 많아진다
     * (detail=HIGH와 맞물려 응답이 특히 느려짐). 글자 판독에는 긴 변 2000px면 충분하므로,
     * 초과분만 줄여서 응답 시간을 단축한다. reasoningEffort·detail은 정확도 때문에 그대로 둔다.
     */
    private static final int MAX_DIMENSION_PX = 2000;
    private static final float JPEG_QUALITY = 0.85f;

    private final AiClient aiClient;
    private final ProductLookupService productLookupService;

    public OcrService(AiClient aiClient, ProductLookupService productLookupService) {
        this.aiClient = aiClient;
        this.productLookupService = productLookupService;
    }

    /** MultipartFile은 응답이 나간 뒤(비동기 처리 중)엔 더 이상 유효하지 않으므로, 요청 스레드에서 미리 바이트로 꺼내 둔다. */
    public record RawInput(byte[] bytes, String mimeType) {
    }

    /** 검증 + 바이트 추출만 한다 — 컨트롤러 스레드에서 동기로 끝나야 하는 부분. */
    public RawInput readAndValidate(MultipartFile file) {
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
        return new RawInput(bytes, file.getContentType());
    }

    /**
     * 실제 AI 호출은 여기서 한다. OcrJobService가 이 메서드를 호출해야 {@code @Async}가 실제로
     * 별도 스레드에서 돈다 — 같은 빈 안에서 자기 자신의 {@code @Async} 메서드를 호출하면
     * 스프링 프록시를 거치지 않아 조용히 동기 실행되는 흔한 함정이라, 호출은 항상 다른 빈
     * (OcrJobService)에서 하도록 구조를 나눴다.
     */
    @Async
    public CompletableFuture<List<OcrResultDto>> processAsync(byte[] bytes, String mimeType) {
        return CompletableFuture.completedFuture(runOcr(bytes, mimeType));
    }

    /** 사진 한 장에서 서로 다른 약 여러 개가 나올 수 있다(약봉투) — 결과는 항목마다 하나씩 목록으로 온다. */
    private List<OcrResultDto> runOcr(byte[] bytes, String mimeType) {
        PreparedImage prepared = resizeForOcr(bytes, mimeType);

        // AI 호출 1건이 몇 초 걸리는지 남긴다 — reasoning effort·이미지 크기 같은 설정을
        // 바꿔가며 실제 응답시간을 눈으로 확인하기 위함(TROUBLESHOOTING.md #24 참고).
        long start = System.currentTimeMillis();
        List<AiClient.OcrExtractionResult> results =
                aiClient.extractMedicationInfo(prepared.bytes(), prepared.mimeType());
        long elapsedMs = System.currentTimeMillis() - start;
        log.info("OCR 호출 완료: {}ms, 원본 {} bytes -> 전송 {} bytes, 인식된 약 {}건",
                elapsedMs, bytes.length, prepared.bytes().length, results.size());

        if (results.isEmpty()) {
            throw new IllegalStateException("사진에서 약 정보를 읽지 못했습니다. 더 선명한 사진으로 다시 시도해주세요.");
        }
        return results.stream().map(this::toDto).toList();
    }

    private record PreparedImage(byte[] bytes, String mimeType) {
    }

    /** 디코딩할 수 없는 형식(예: WEBP는 표준 ImageIO 플러그인이 없음)이면 원본을 그대로 돌려준다. */
    private PreparedImage resizeForOcr(byte[] original, String mimeType) {
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(original));
            if (image == null) {
                return new PreparedImage(original, mimeType);
            }
            int width = image.getWidth();
            int height = image.getHeight();
            int longestSide = Math.max(width, height);
            if (longestSide <= MAX_DIMENSION_PX) {
                return new PreparedImage(original, mimeType);
            }

            double scale = (double) MAX_DIMENSION_PX / longestSide;
            int newWidth = Math.max(1, (int) Math.round(width * scale));
            int newHeight = Math.max(1, (int) Math.round(height * scale));

            BufferedImage scaled = new BufferedImage(newWidth, newHeight, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = scaled.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(image, 0, 0, newWidth, newHeight, null);
            g.dispose();

            byte[] jpeg = encodeJpeg(scaled);
            log.debug("OCR 이미지 리사이즈: {}x{} -> {}x{} ({} -> {} bytes)",
                    width, height, newWidth, newHeight, original.length, jpeg.length);
            return new PreparedImage(jpeg, "image/jpeg");
        } catch (IOException e) {
            log.warn("OCR 이미지 리사이즈 실패, 원본을 그대로 사용합니다: {}", e.getMessage());
            return new PreparedImage(original, mimeType);
        }
    }

    private static byte[] encodeJpeg(BufferedImage image) throws IOException {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpg");
        ImageWriter writer = writers.next();
        try {
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(JPEG_QUALITY);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (ImageOutputStream ios = ImageIO.createImageOutputStream(out)) {
                writer.setOutput(ios);
                writer.write(null, new IIOImage(image, null, null), param);
            }
            return out.toByteArray();
        } finally {
            writer.dispose();
        }
    }

    private OcrResultDto toDto(AiClient.OcrExtractionResult r) {
        List<IngredientDto> aiIngredients = r.ingredients() == null ? List.of() : r.ingredients().stream()
                .map(i -> new IngredientDto(i.name(), i.englishName(), i.amount(), i.unit()))
                .toList();

        // AI가 사진 속 성분·함량표를 눈으로 읽는 대신, 읽어낸 제품명으로 우리 DB(식약처 허가
        // 원본 데이터)를 먼저 찾아본다(1차 전체 이름, 실패하면 2차 브랜드명 폴백 — §26·§27,
        // ProductLookupService에 정리됨). 찾으면 그 값이 더 정확하므로 AI가 읽은 성분을
        // 대체하고, 못 찾으면(수집 범위 밖 제품) 기존처럼 AI가 읽은 값을 그대로 쓴다.
        List<ProductIngredientRow> dbRows = productLookupService.findIngredients(r.productName());
        boolean ingredientsFromDb = !dbRows.isEmpty();
        List<IngredientDto> ingredients = ingredientsFromDb
                ? dbRows.stream().map(row -> new IngredientDto(row.nameKo(), row.nameEn(), row.amount(), row.unit())).toList()
                : aiIngredients;
        if (ingredientsFromDb) {
            log.debug("OCR 성분을 DB에서 확인함: 제품명={}, 성분 {}개", r.productName(), ingredients.size());
        }

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
            // DB에서 확인된 성분은 식약처 허가 원본이라 신뢰도를 최대로 표시한다.
            Double confidence = ingredientsFromDb ? 1.0 : r.ingredientsConfidence();
            rows.add(new OcrRowDto("성분" + (ingredientsFromDb ? " (DB 확인)" : ""), value, confidence));
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
