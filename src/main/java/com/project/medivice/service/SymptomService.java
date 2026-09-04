package com.project.medivice.service;

import com.project.medivice.dto.SymptomCreateRequest;
import com.project.medivice.dto.SymptomDto;
import com.project.medivice.dto.SymptomDto.MedicationRefDto;
import com.project.medivice.repository.MedicationRepository;
import com.project.medivice.repository.MedicationRepository.MedicationNameRow;
import com.project.medivice.repository.SymptomRepository;
import com.project.medivice.repository.SymptomRepository.LogRow;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sprint 2-C(UC20~22)는 외부 의존이 없는 순수 CRUD라 "여유가 있으면 실제 구현으로 승격"이 권장된
 * 그룹이고, 테이블이 이미 있어 그대로 구현했다(SymptomRepository 주석 참고).
 */
@Service
public class SymptomService {

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");

    private final SymptomRepository symptomRepository;
    private final MedicationRepository medicationRepository;
    private final DemoUserResolver demoUserResolver;

    public SymptomService(SymptomRepository symptomRepository, MedicationRepository medicationRepository,
            DemoUserResolver demoUserResolver) {
        this.symptomRepository = symptomRepository;
        this.medicationRepository = medicationRepository;
        this.demoUserResolver = demoUserResolver;
    }

    public List<SymptomDto> list(Long userId, int limit) {
        return symptomRepository.findRecentByUser(userId, limit).stream().map(this::toDto).toList();
    }

    /** UC29 보고서의 기간 내 증상 타임라인. */
    public List<SymptomDto> listInRange(Long userId, LocalDate from, LocalDate to) {
        return symptomRepository.findByUserAndRange(userId, from, to).stream().map(this::toDto).toList();
    }

    @Transactional
    public SymptomDto create(SymptomCreateRequest request) {
        Long userId = demoUserResolver.resolveUserId();
        Long logId = symptomRepository.insertLog(userId, request.date(), request.note());

        for (String name : request.symptoms()) {
            Integer symptomId = symptomRepository.findOrCreateSymptomId(name);
            symptomRepository.insertLogSymptom(logId, symptomId);
        }

        // UC21: 사용자는 약을 고르지 않는다. 저장 시점의 복용 목록을 값 복사로 스냅샷한다.
        List<MedicationNameRow> activeMedications = medicationRepository.findActiveIdsAndNames(userId);
        for (MedicationNameRow med : activeMedications) {
            symptomRepository.insertSnapshot(logId, med.id(), med.name());
        }
        List<MedicationRefDto> snapshot = activeMedications.stream()
                .map(m -> new MedicationRefDto(String.valueOf(m.id()), m.name()))
                .toList();

        return new SymptomDto(String.valueOf(logId), request.date().toString(), request.time(),
                request.symptoms(), request.note(), snapshot);
    }

    private SymptomDto toDto(LogRow log) {
        List<String> names = symptomRepository.findSymptomNames(log.logId());
        List<MedicationRefDto> snapshot = symptomRepository.findSnapshot(log.logId()).stream()
                .map(s -> new MedicationRefDto(
                        s.medicationId() != null ? String.valueOf(s.medicationId()) : null, s.productName()))
                .toList();
        String time = log.writtenAt() != null ? log.writtenAt().toLocalTime().format(TIME_FORMAT) : null;
        return new SymptomDto(String.valueOf(log.logId()), log.occurredDate().toString(), time, names,
                log.note(), snapshot);
    }
}
