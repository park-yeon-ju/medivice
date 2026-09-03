package com.project.medivice.controller;

import com.project.medivice.dto.MedicationCreateRequest;
import com.project.medivice.dto.MedicationCreateResponse;
import com.project.medivice.dto.OcrJobDto;
import com.project.medivice.service.MedicationService;
import com.project.medivice.service.OcrJobService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 프론트 계약: POST /api/medications → 201, DELETE /api/medications/:id → 204,
 * POST /api/medications/ocr → 200(EXT-1). UC13 수기 등록이 여전히 실제 저장이 일어나는 유일한
 * 경로다 — OCR은 확인 화면에 보여줄 초안만 만들고, 등록은 사용자가 확인한 뒤 POST /api/medications로 한다.
 */
@Tag(name = "복용 항목", description = "약 등록(수기·사진)·삭제. 등록은 항상 성분 목록(복합제 지원)을 받는다")
@RestController
@RequestMapping("/api/medications")
public class MedicationController {

    private final MedicationService medicationService;
    private final OcrJobService ocrJobService;

    public MedicationController(MedicationService medicationService, OcrJobService ocrJobService) {
        this.medicationService = medicationService;
        this.ocrJobService = ocrJobService;
    }

    @Operation(summary = "복용 항목 등록", description = "수기 입력 또는 OCR 확인 화면에서 사용자가 확인한 값을 실제로 저장한다. 성분은 배열로 받아 복합제(성분 2개 이상)를 지원한다. 응답에 등록 직후 재계산된 medilight 판정이 함께 온다.")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public MedicationCreateResponse create(@Valid @RequestBody MedicationCreateRequest request) {
        return medicationService.create(request);
    }

    @Operation(summary = "복용 항목 삭제", description = "소프트 삭제(ended_at 기록)라 과거 증상 기록의 복용 스냅샷은 그대로 남는다. 응답 본문이 없으므로 최신 판정은 GET /api/medilight로 다시 조회해야 한다.")
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@Parameter(description = "삭제할 medication_id") @PathVariable Long id) {
        medicationService.delete(id);
    }

    @Operation(
            summary = "사진으로 약 정보 인식(OCR) 요청 — 비동기",
            description = """
                    처방전·약봉투·제품 라벨 사진을 비전 AI(medivice.ai.provider 설정에 따라 openai 또는 mock)로
                    분석한다. AI 응답에 수십 초가 걸릴 수 있어 요청은 즉시 202로 끝나고, 실제 인식은
                    백그라운드에서 진행된다(PENDING → PROCESSING → COMPLETED/FAILED). 결과는
                    GET /api/medications/ocr/{jobId}로 폴링해서 받는다. 여기서는 아무것도 저장하지
                    않는다 — 완료된 값을 사용자가 확인·수정한 뒤 POST /api/medications로 항목마다
                    따로 등록해야 한다.""")
    @PostMapping(value = "/ocr", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    public OcrJobDto ocr(
            @Parameter(description = "JPG/PNG/WEBP, 최대 10MB", schema = @Schema(type = "string", format = "binary"))
            @RequestParam("file") MultipartFile file) {
        String jobId = ocrJobService.submit(file);
        return new OcrJobDto(jobId, "PENDING", null, null);
    }

    @Operation(
            summary = "OCR 작업 상태 조회",
            description = """
                    POST /api/medications/ocr가 돌려준 jobId로 진행 상태를 확인한다. status가
                    COMPLETED면 result에 인식된 약 목록이, FAILED면 error에 실패 사유가 채워진다.
                    PENDING·PROCESSING이면 둘 다 null이니 프론트는 잠시 뒤 다시 호출하면 된다.""")
    @GetMapping("/ocr/{jobId}")
    public OcrJobDto ocrJob(@Parameter(description = "POST /ocr 응답의 jobId") @PathVariable String jobId) {
        return ocrJobService.getJob(jobId);
    }
}
