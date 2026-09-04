package com.project.medivice.service;

import com.project.medivice.config.MediviceProperties;
import com.project.medivice.dto.ConflictDto;
import com.project.medivice.dto.IngredientAnalysisDto;
import com.project.medivice.dto.IngredientSourceDto;
import com.project.medivice.dto.MedilightDto;
import com.project.medivice.dto.UncoveredIngredientDto;
import com.project.medivice.repository.MedilightViewRepository;
import com.project.medivice.repository.MedilightViewRepository.ActiveIngredientRow;
import com.project.medivice.repository.MedilightViewRepository.EffectDupRow;
import com.project.medivice.repository.MedilightViewRepository.OverallRow;
import com.project.medivice.repository.MedilightViewRepository.OverdoseRow;
import com.project.medivice.repository.MedilightViewRepository.PairConflictRow;
import com.project.medivice.repository.MedilightViewRepository.SingleConflictRow;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;

/**
 * UC15·UC16 판정 결과를 03_medilight_views.sql 의 뷰에서 읽어 프론트 계약(MedilightDto) 모양으로
 * 조립한다. 색과 판정 사유 자체는 여기서 계산하지 않는다 — DB 뷰가 곧 규칙 엔진이고, 이 클래스는
 * 그 결과를 화면이 원하는 JSON 모양으로 바꾸는 뷰 모델일 뿐이다(스프린트 계획 축 2).
 */
@Service
public class MedilightService {

    private static final String DUR_REFERENCE = "식약처 DUR(용량주의) · 의약품안전나라";

    private final MedilightViewRepository viewRepository;
    private final MediviceProperties properties;

    public MedilightService(MedilightViewRepository viewRepository, MediviceProperties properties) {
        this.viewRepository = viewRepository;
        this.properties = properties;
    }

    public MedilightDto build(Long userId) {
        List<IngredientAnalysisDto> totals = buildTotals(userId);
        List<IngredientAnalysisDto> findings = totals.stream().filter(t -> !"OK".equals(t.status())).toList();
        List<ConflictDto> conflicts = buildConflicts(userId);

        OverallRow overall = viewRepository.findOverall(userId);
        String status = mapLevel(overall.level());
        int issueCount = findings.size() + conflicts.size();
        String summary = switch (status) {
            case "CRIT" -> "전문가 확인이 필요한 항목 " + issueCount + "건";
            case "WARN" -> "확인이 필요한 항목 " + issueCount + "건";
            default -> "현재 규칙에서 확인된 문제 없음";
        };

        String noticeMessage = viewRepository.findNoticeMessage(userId).orElse(null);
        String checkedAt = viewRepository.findLatestCheckedAt(userId)
                .map(dt -> dt.toLocalDate().toString())
                .orElse(LocalDate.now().toString());
        // §37: noticeMessage는 콤마로 성분명을 이어붙인 한 문장이라, 프론트가 성분별로 나눠
        // 보여주고 싶어도 문장을 다시 파싱해야 한다 — 같은 데이터를 구조화된 배열로도 내려준다.
        List<UncoveredIngredientDto> uncoveredIngredients = viewRepository.findUncoveredIngredients(userId).stream()
                .map(r -> new UncoveredIngredientDto(r.nameKo(), r.nameEn()))
                .toList();

        return new MedilightDto(status, summary, findings, totals, conflicts,
                properties.ruleVersion(), checkedAt, overall.uncoveredCount(), noticeMessage, uncoveredIngredients);
    }

    /** v_active_ingredients(약 단위)를 성분 단위로 묶고, v_overdose로 상태를 매긴다. */
    private List<IngredientAnalysisDto> buildTotals(Long userId) {
        List<ActiveIngredientRow> active = viewRepository.findActiveIngredients(userId);
        Map<Long, OverdoseRow> overdoseByIngredient = new LinkedHashMap<>();
        for (OverdoseRow row : viewRepository.findOverdose(userId)) {
            overdoseByIngredient.put(row.ingredientId(), row);
        }

        Map<Long, List<ActiveIngredientRow>> grouped = new LinkedHashMap<>();
        for (ActiveIngredientRow row : active) {
            grouped.computeIfAbsent(row.ingredientId(), k -> new ArrayList<>()).add(row);
        }

        List<IngredientAnalysisDto> totals = new ArrayList<>();
        for (Map.Entry<Long, List<ActiveIngredientRow>> entry : grouped.entrySet()) {
            List<ActiveIngredientRow> rows = entry.getValue();
            ActiveIngredientRow first = rows.get(0);
            BigDecimal dailyTotal = rows.stream()
                    .map(ActiveIngredientRow::dailyAmount)
                    .filter(Objects::nonNull)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            List<IngredientSourceDto> sources = rows.stream()
                    .map(r -> new IngredientSourceDto(r.productName(), r.dailyAmount(), r.dosePerIntake(), r.timesPerDay()))
                    .toList();

            OverdoseRow od = overdoseByIngredient.get(entry.getKey());
            String status;
            String reasonCode;
            BigDecimal upperLimit = null;
            Double ratio = null;
            String unit = first.unit();
            String reference = null;

            if (od != null) {
                upperLimit = od.maxQty();
                unit = od.unit() != null ? od.unit() : first.unit();
                ratio = upperLimit != null && upperLimit.signum() != 0
                        ? dailyTotal.doubleValue() / upperLimit.doubleValue()
                        : null;
                reference = upperLimit != null ? DUR_REFERENCE : null;
                if ("RED".equals(od.level())) {
                    status = "CRIT";
                    reasonCode = "OVER_LIMIT";
                } else if (od.medCount() != null && od.medCount() >= 2) {
                    status = "WARN";
                    reasonCode = "DUPLICATE";
                } else {
                    status = "WARN";
                    reasonCode = "NEAR_LIMIT";
                }
            } else {
                status = "OK";
                reasonCode = null;
            }

            totals.add(new IngredientAnalysisDto(first.nameKo(), first.nameEn(), unit, dailyTotal,
                    sources, status, reasonCode, upperLimit, reference, ratio));
        }
        return totals;
    }

    /** v_pair_conflict(병용금기·효능군중복) + v_single_conflict(임부·연령·노인) → 근거 배열. */
    private List<ConflictDto> buildConflicts(Long userId) {
        List<ConflictDto> conflicts = new ArrayList<>();
        for (PairConflictRow r : viewRepository.findPairConflicts(userId)) {
            // §39: "대상" 칸엔 실제로 충돌하는 성분(ingredientA/B)을 우선 보여준다 — 제품명
            // (medicationA/B)은 "어느 등록 항목 때문인지" 추적용으로 같이 내려주되, 화면은
            // 성분명을 우선 쓴다(ConflictDto.ingredientA/B가 있으면 그걸 먼저 본다).
            conflicts.add(new ConflictDto(r.durTypeName(), mapLevel(r.level()),
                    r.ingredientAName(), r.ingredientBName(),
                    r.medicationAName(), r.medicationBName(), r.prohibitContent()));
        }
        for (SingleConflictRow r : viewRepository.findSingleConflicts(userId)) {
            conflicts.add(new ConflictDto(r.durTypeName(), mapLevel(r.level()), r.ingredientName(), null,
                    null, null, r.prohibitContent()));
        }
        for (EffectDupRow r : viewRepository.findEffectDup(userId)) {
            conflicts.add(new ConflictDto("효능군중복", mapLevel(r.level()), null, null,
                    r.medicationAName(), r.medicationBName(), r.effectName() + " 계열이 중복됩니다."));
        }
        return conflicts;
    }

    /** DB 뷰의 색 어휘(GREEN/YELLOW/RED)를 프론트 계약의 상태 어휘(OK/WARN/CRIT)로 옮긴다. */
    private static String mapLevel(String level) {
        if (level == null) {
            return "OK";
        }
        return switch (level) {
            case "RED" -> "CRIT";
            case "YELLOW" -> "WARN";
            default -> "OK";
        };
    }
}
