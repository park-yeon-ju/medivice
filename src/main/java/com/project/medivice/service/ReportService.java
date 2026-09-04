package com.project.medivice.service;

import com.project.medivice.ai.AiClient;
import com.project.medivice.ai.AiClient.ReportContext;
import com.project.medivice.dto.MedilightDto;
import com.project.medivice.dto.ReportCreateRequest;
import com.project.medivice.dto.ReportDto;
import com.project.medivice.dto.SymptomDto;
import com.project.medivice.repository.MedicationRepository;
import com.project.medivice.repository.ReportRepository;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * UC28·UC29 진료용 보고서. Sprint 2 DoD가 요구하는 PENDING → PROCESSING → COMPLETED/FAILED 상태
 * 흐름 중, 실제 비동기 워커는 이번 범위 밖이라(EXT-3, Sprint 2는 명세+Mock) 요청 스레드 안에서
 * 규칙 엔진 결과(medilightService)를 집계하고 MockAiClient로 즉시 COMPLETED까지 만든다.
 * 프론트는 jobStatus 필드만 보므로, 나중에 실제 비동기 워커를 붙여도 이 DTO 모양은 그대로다.
 */
@Service
public class ReportService {

    private static final DateTimeFormatter GENERATED_AT_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final ReportRepository reportRepository;
    private final MedicationRepository medicationRepository;
    private final SymptomService symptomService;
    private final MedilightService medilightService;
    private final AiClient aiClient;
    private final DemoUserResolver demoUserResolver;
    private final TranslationService translationService;

    public ReportService(ReportRepository reportRepository, MedicationRepository medicationRepository,
            SymptomService symptomService, MedilightService medilightService, AiClient aiClient,
            DemoUserResolver demoUserResolver, TranslationService translationService) {
        this.reportRepository = reportRepository;
        this.medicationRepository = medicationRepository;
        this.symptomService = symptomService;
        this.medilightService = medilightService;
        this.aiClient = aiClient;
        this.demoUserResolver = demoUserResolver;
        this.translationService = translationService;
    }

    @Transactional
    public ReportDto create(ReportCreateRequest request) {
        Long userId = demoUserResolver.resolveUserId();
        // reports.language는 소문자 ko/en만 허용한다(CHECK 제약). 프론트는 표시용으로 'KO'/'EN'을
        // 보내므로(SCR-RPT-001 언어 선택), 대소문자를 신뢰하지 않고 여기서 정규화한다.
        String language = request.language() != null ? request.language().toLowerCase() : "ko";
        if (!"ko".equals(language) && !"en".equals(language)) {
            language = "ko";
        }

        int medicationCount = medicationRepository.findActiveIdsAndNames(userId).size();
        MedilightDto medilight = medilightService.build(userId);
        int warnCount = countLevel(medilight, "WARN");
        int critCount = countLevel(medilight, "CRIT");
        List<SymptomDto> symptoms = symptomService.listInRange(userId, request.from(), request.to());
        // narrative(AI 요약)는 AiClient가 language별로 알아서 만들어 주지만, symptoms는 사용자가
        // 직접 적은 한국어 원문이 그대로 실린다 — language=en일 때만 DeepL로 옮겨서 보고서
        // 전체가 한 언어로 보이게 한다(키가 없으면 TranslationService가 원문을 그대로 돌려준다).
        if ("en".equals(language)) {
            symptoms = translateSymptoms(symptoms);
        }

        // EXT-3: AI는 이미 집계된 숫자만 문장으로 풀어쓴다(색·수치는 규칙 엔진이 이미 정함).
        String narrative = aiClient.summarizeReport(
                new ReportContext(medicationCount, warnCount, critCount, symptoms.size(), language));

        Long reportId = reportRepository.insert(userId, request.from(), request.to(), language, "completed");
        String generatedAt = LocalDateTime.now().format(GENERATED_AT_FORMAT);

        return new ReportDto(String.valueOf(reportId), "COMPLETED", generatedAt,
                request.from().toString(), request.to().toString(), language,
                medicationCount, warnCount, critCount, symptoms, narrative);
    }

    /**
     * 증상 기록 여러 건의 symptoms(증상명 목록)·note를 한 줄로 펼쳐 DeepL 호출 1회로 번역한 뒤,
     * 다시 건별로 잘라 담는다 — 증상 기록이 몇 건이든 API 호출은 항상 1회다.
     */
    private List<SymptomDto> translateSymptoms(List<SymptomDto> symptoms) {
        if (symptoms.isEmpty() || !translationService.isEnabled()) {
            return symptoms;
        }
        List<String> texts = new ArrayList<>();
        for (SymptomDto s : symptoms) {
            texts.addAll(s.symptoms());
            texts.add(s.note() == null ? "" : s.note());
        }
        List<String> translated = translationService.translateAll(texts, "en");

        List<SymptomDto> result = new ArrayList<>();
        int cursor = 0;
        for (SymptomDto s : symptoms) {
            int count = s.symptoms().size();
            List<String> translatedNames = List.copyOf(translated.subList(cursor, cursor + count));
            cursor += count;
            String translatedNote = translated.get(cursor);
            cursor += 1;
            result.add(new SymptomDto(s.id(), s.date(), s.time(), translatedNames,
                    translatedNote.isBlank() ? null : translatedNote, s.medicationSnapshot()));
        }
        return result;
    }

    private static int countLevel(MedilightDto medilight, String level) {
        long findingCount = medilight.findings().stream().filter(f -> level.equals(f.status())).count();
        long conflictCount = medilight.conflicts().stream().filter(c -> level.equals(c.level())).count();
        return (int) (findingCount + conflictCount);
    }
}
