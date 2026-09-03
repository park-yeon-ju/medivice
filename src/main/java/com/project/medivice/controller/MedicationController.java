package com.project.medivice.controller;

import com.project.medivice.dto.MedicationCreateRequest;
import com.project.medivice.dto.MedicationCreateResponse;
import com.project.medivice.dto.OcrResultDto;
import com.project.medivice.service.MedicationService;
import com.project.medivice.service.OcrService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
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
@RestController
@RequestMapping("/api/medications")
public class MedicationController {

    private final MedicationService medicationService;
    private final OcrService ocrService;

    public MedicationController(MedicationService medicationService, OcrService ocrService) {
        this.medicationService = medicationService;
        this.ocrService = ocrService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public MedicationCreateResponse create(@Valid @RequestBody MedicationCreateRequest request) {
        return medicationService.create(request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        medicationService.delete(id);
    }

    @PostMapping(value = "/ocr", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public List<OcrResultDto> ocr(@RequestParam("file") MultipartFile file) {
        return ocrService.extract(file);
    }
}
