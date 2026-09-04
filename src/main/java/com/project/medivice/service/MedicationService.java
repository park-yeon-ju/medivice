package com.project.medivice.service;

import com.project.medivice.ai.AiClient;
import com.project.medivice.dto.IngredientDto;
import com.project.medivice.dto.MedicationCreateRequest;
import com.project.medivice.dto.MedicationCreateResponse;
import com.project.medivice.dto.MedicationDto;
import com.project.medivice.dto.MedilightDto;
import com.project.medivice.exception.NotFoundException;
import com.project.medivice.repository.AiOutputRepository;
import com.project.medivice.repository.DepartmentRepository;
import com.project.medivice.repository.IngredientRepository;
import com.project.medivice.repository.MedicationRepository;
import com.project.medivice.repository.MedicationRepository.IngredientRow;
import com.project.medivice.repository.MedicationRepository.MedicationHeaderRow;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * UC13(수기 등록) · UC8~12(촬영/OCR 등록) · UC14(삭제) · UC18(목록 조회).
 * product_id 매칭 없이 항상 custom_name + 성분 목록(복합제 지원)을 받는다.
 */
@Service
public class MedicationService {

    private static final Logger log = LoggerFactory.getLogger(MedicationService.class);

    private final MedicationRepository medicationRepository;
    private final IngredientRepository ingredientRepository;
    private final DepartmentRepository departmentRepository;
    private final DemoUserResolver demoUserResolver;
    private final MedilightService medilightService;
    private final SafetyCheckService safetyCheckService;
    private final AiClient aiClient;
    private final ProductLookupService productLookupService;
    private final AiOutputRepository aiOutputRepository;

    public MedicationService(MedicationRepository medicationRepository, IngredientRepository ingredientRepository,
            DepartmentRepository departmentRepository, DemoUserResolver demoUserResolver,
            MedilightService medilightService, SafetyCheckService safetyCheckService,
            AiClient aiClient, ProductLookupService productLookupService, AiOutputRepository aiOutputRepository) {
        this.medicationRepository = medicationRepository;
        this.ingredientRepository = ingredientRepository;
        this.departmentRepository = departmentRepository;
        this.demoUserResolver = demoUserResolver;
        this.medilightService = medilightService;
        this.safetyCheckService = safetyCheckService;
        this.aiClient = aiClient;
        this.productLookupService = productLookupService;
        this.aiOutputRepository = aiOutputRepository;
    }

    /** DoD: "목록이 처방약(병원·진료과별) → 영양제·상비약 순으로 정렬된다" — 정렬은 리포지토리 쿼리가 보장한다. */
    public List<MedicationDto> list(Long userId) {
        List<MedicationHeaderRow> headers = medicationRepository.findActiveByUser(userId);
        Map<Long, List<IngredientRow>> byMedication = new LinkedHashMap<>();
        for (IngredientRow row : medicationRepository.findIngredientRowsByUser(userId)) {
            byMedication.computeIfAbsent(row.medicationId(), k -> new ArrayList<>()).add(row);
        }
        // §29: 등록 시점에 이미 생성해 ai_outputs에 캐시해 둔 설명을 읽기만 한다 — 목록 조회
        // 때마다 AI를 다시 부르지 않는다. 이 기능 이전에 등록된 항목은 그냥 설명 없이 보인다.
        List<Long> medicationIds = headers.stream().map(MedicationHeaderRow::medicationId).toList();
        Map<Long, String> explanations = aiOutputRepository.findExplanations(medicationIds);
        List<MedicationDto> result = new ArrayList<>();
        for (MedicationHeaderRow header : headers) {
            result.add(toDto(header, byMedication.getOrDefault(header.medicationId(), List.of()),
                    explanations.get(header.medicationId())));
        }
        return result;
    }

    @Transactional
    public MedicationCreateResponse create(MedicationCreateRequest request) {
        Long userId = demoUserResolver.resolveUserId();
        boolean isPrescription = "PRESCRIPTION".equals(request.type());

        Long prescriptionId = null;
        String registerReason = null;
        if (isPrescription) {
            Integer departmentId = departmentRepository.findIdByName(request.department()).orElse(null);
            prescriptionId = medicationRepository.insertPrescription(
                    userId, request.hospital(), departmentId, request.reason(), request.duration());
        } else {
            registerReason = request.reason();
        }

        Long medicationId = medicationRepository.insertMedication(userId, prescriptionId, request.name(),
                request.type(), request.timing(), request.doseUnit(), request.dose(), request.timesPerDay(),
                registerReason);

        // 복합제는 성분이 여러 개다(예: 아모잘탄정 = 암로디핀 + 로사르탄칼륨). 하나로 뭉개지 않고
        // 각각을 medication_ingredients에 넣어야 성분별 하루 총량 판정(UC15)이 정확해진다.
        // DUR 마스터에 있는 성분이면 그 id를 재사용해야 병용금기·중복 판정에도 걸린다(IngredientRepository 참고).
        for (MedicationCreateRequest.IngredientInput ingredient : request.ingredients()) {
            Long ingredientId = ingredientRepository.findOrCreateByName(ingredient.name());
            medicationRepository.insertMedicationIngredient(medicationId, ingredientId,
                    ingredient.amount(), ingredient.unit());
        }

        safetyCheckService.recordCheck(userId, "REGISTER");

        List<IngredientDto> ingredientDtos = request.ingredients().stream()
                .map(i -> new IngredientDto(i.name(), null, i.amount(), i.unit()))
                .toList();

        List<String> ingredientNames = request.ingredients().stream()
                .map(MedicationCreateRequest.IngredientInput::name)
                .toList();
        String aiExplanation = generateExplanation(medicationId, request.name(), ingredientNames);

        MedicationDto dto = new MedicationDto(
                String.valueOf(medicationId),
                request.type(),
                request.name(),
                ingredientDtos,
                null,
                request.dose(),
                request.doseUnit(),
                request.timesPerDay(),
                request.timing(),
                isPrescription ? request.hospital() : null,
                isPrescription ? request.department() : null,
                request.reason(),
                LocalDate.now().toString(),
                isPrescription ? request.duration() : null,
                !isPrescription,
                aiExplanation);

        MedilightDto medilight = medilightService.build(userId);
        return new MedicationCreateResponse(dto, medilight);
    }

    /**
     * UC14 보완: 복용 항목 수정.
     * 용량·횟수·성분 목록 등을 변경하고, 의약품 안전성(Medilight)을 즉시 재계산하여 반환한다.
     */
    @Transactional
    public MedicationCreateResponse update(Long medicationId, MedicationCreateRequest request) {
        Long userId = demoUserResolver.resolveUserId();
        MedicationHeaderRow existing = medicationRepository.findActiveById(medicationId, userId)
                .orElseThrow(() -> new NotFoundException("복용 항목을 찾을 수 없습니다: id=" + medicationId));

        boolean isPrescription = "PRESCRIPTION".equals(request.type());
        String registerReason = null;
        if (isPrescription) {
            Integer departmentId = departmentRepository.findIdByName(request.department()).orElse(null);
            if (existing.prescriptionId() != null) {
                medicationRepository.updatePrescription(
                        existing.prescriptionId(), request.hospital(), departmentId, request.reason(), request.duration());
            } else {
                Long newPrescriptionId = medicationRepository.insertPrescription(
                        userId, request.hospital(), departmentId, request.reason(), request.duration());
                medicationRepository.attachPrescription(medicationId, newPrescriptionId);
            }
        } else {
            registerReason = request.reason();
        }

        medicationRepository.updateMedication(medicationId, userId, request.name(),
                request.type(), request.timing(), request.doseUnit(), request.dose(), request.timesPerDay(),
                registerReason);

        medicationRepository.deleteMedicationIngredients(medicationId);
        for (MedicationCreateRequest.IngredientInput ingredient : request.ingredients()) {
            Long ingredientId = ingredientRepository.findOrCreateByName(ingredient.name());
            medicationRepository.insertMedicationIngredient(medicationId, ingredientId,
                    ingredient.amount(), ingredient.unit());
        }

        safetyCheckService.recordCheck(userId, "UPDATE");

        List<IngredientDto> ingredientDtos = request.ingredients().stream()
                .map(i -> new IngredientDto(i.name(), null, i.amount(), i.unit()))
                .toList();

        List<String> ingredientNames = request.ingredients().stream()
                .map(MedicationCreateRequest.IngredientInput::name)
                .toList();
        String aiExplanation = generateExplanation(medicationId, request.name(), ingredientNames);

        MedicationDto dto = new MedicationDto(
                String.valueOf(medicationId),
                request.type(),
                request.name(),
                ingredientDtos,
                null,
                request.dose(),
                request.doseUnit(),
                request.timesPerDay(),
                request.timing(),
                isPrescription ? request.hospital() : null,
                isPrescription ? request.department() : null,
                request.reason(),
                existing.startedAt() != null ? existing.startedAt().toString() : LocalDate.now().toString(),
                isPrescription ? request.duration() : null,
                !isPrescription,
                aiExplanation);

        MedilightDto medilight = medilightService.build(userId);
        return new MedicationCreateResponse(dto, medilight);
    }

    /**
     * §29·§31: 등록 직후 "이 약이 뭘 위한 약인지" + "복용 중 흔히 느낄 수 있는 것"을 만들어
     * ai_outputs에 캐시해 둔다. 등록 자체는 이 기능 없이도 되던 핵심 경로라, AI 호출이
     * 실패해도(키 미설정·네트워크 등) 등록 트랜잭션을 굴리지 않는다 — 실패 기록만 남기고
     * aiExplanation은 null로 돌아간다.
     */
    private String generateExplanation(Long medicationId, String productName, List<String> ingredientNames) {
        String prompt = "explainMedication: " + productName;
        try {
            var productInfo = productLookupService.findProductInfo(productName).orElse(null);
            String efficacy = productInfo != null ? productInfo.efficacy() : null;
            String sideEffect = productInfo != null ? productInfo.sideEffect() : null;
            String explanation = aiClient.explainMedication(
                    new AiClient.MedicationExplainContext(productName, ingredientNames, efficacy, sideEffect));
            if (explanation == null || explanation.isBlank()) {
                aiOutputRepository.saveMedicationExplanationFailure(medicationId, prompt, "빈 응답");
                return null;
            }
            aiOutputRepository.saveMedicationExplanation(medicationId, prompt, explanation);
            return explanation;
        } catch (Exception e) {
            log.warn("약 설명 생성 실패(등록은 계속 진행): medicationId={}, error={}", medicationId, e.getMessage());
            aiOutputRepository.saveMedicationExplanationFailure(medicationId, prompt, e.getMessage());
            return null;
        }
    }

    /** UC14: 소프트 삭제 후 전체 재계산(safety_checks 스냅샷)까지 이 트랜잭션 안에서 끝낸다. */
    @Transactional
    public void delete(Long medicationId) {
        Long userId = demoUserResolver.resolveUserId();
        int updated = medicationRepository.softDelete(medicationId, userId);
        if (updated == 0) {
            throw new NotFoundException("복용 항목을 찾을 수 없습니다: id=" + medicationId);
        }
        safetyCheckService.recordCheck(userId, "DELETE");
    }

    private MedicationDto toDto(MedicationHeaderRow header, List<IngredientRow> ingredientRows, String aiExplanation) {
        String type = resolveType(header);
        String name = header.productName() != null ? header.productName() : header.customName();
        List<IngredientDto> ingredients = ingredientRows.stream()
                .map(r -> new IngredientDto(r.nameKo(), r.nameEn(), r.amount(), r.unit()))
                .toList();
        String reason = header.prescriptionId() != null ? header.reasonDetail() : header.registerReason();

        return new MedicationDto(
                String.valueOf(header.medicationId()),
                type,
                name,
                ingredients,
                null,
                header.dosePerIntake(),
                header.doseUnit(),
                header.timesPerDay(),
                header.timing(),
                header.hospitalName(),
                header.departmentName(),
                reason,
                header.startedAt() != null ? header.startedAt().toString() : null,
                header.durationNote(),
                !"PRESCRIPTION".equals(type),
                aiExplanation);
    }

    /** UC13 수기 등록은 custom_type을 그대로 쓰고, 마스터 매칭분은 products.product_type을 옮긴다. */
    private static String resolveType(MedicationHeaderRow header) {
        if (header.customType() != null) {
            return header.customType();
        }
        if (header.productType() != null) {
            return switch (header.productType()) {
                case "ETC" -> "PRESCRIPTION";
                case "SUPPLEMENT" -> "SUPPLEMENT";
                default -> "OTC";
            };
        }
        return "OTC";
    }
}
