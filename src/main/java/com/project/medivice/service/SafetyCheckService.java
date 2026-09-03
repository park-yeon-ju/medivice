package com.project.medivice.service;

import com.project.medivice.repository.MedilightViewRepository;
import com.project.medivice.repository.MedilightViewRepository.OverallRow;
import com.project.medivice.repository.MedilightViewRepository.OverdoseRow;
import com.project.medivice.repository.MedilightViewRepository.SingleConflictRow;
import com.project.medivice.repository.MedilightViewRepository.UncoveredRow;
import com.project.medivice.repository.SafetyCheckRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * DoD: "판정 사유에 계산식과 기준 출처·확인일이 함께 표시된다". 등록·삭제마다 그 시점의 판정
 * 스냅샷을 safety_checks/safety_check_items 에 남겨 "확인일"의 근거로 삼는다.
 *
 * safety_check_items.ingredient_id는 NOT NULL이라, 성분 하나로 귀결되는 원인(용량주의 중복,
 * 임부·연령·노인 단항 금기, 판정 근거 없음)만 상세 기록한다. 병용금기·효능군중복은 성분 쌍이 아니라
 * 두 복용 항목의 조합이라 이 스냅샷 테이블 모양에 맞지 않으므로, 그 두 유형은 상위 safety_checks
 * 헤더의 level(전체 색)에는 반영되지만 items 상세로는 남기지 않는다 — MedilightService가 매 요청마다
 * 뷰에서 직접 다시 계산해 보여주므로 화면 표시에는 영향이 없다.
 */
@Service
public class SafetyCheckService {

    private final MedilightViewRepository viewRepository;
    private final SafetyCheckRepository safetyCheckRepository;

    public SafetyCheckService(MedilightViewRepository viewRepository, SafetyCheckRepository safetyCheckRepository) {
        this.viewRepository = viewRepository;
        this.safetyCheckRepository = safetyCheckRepository;
    }

    @Transactional
    public void recordCheck(Long userId, String triggerType) {
        OverallRow overall = viewRepository.findOverall(userId);
        Long checkId = safetyCheckRepository.insertCheck(userId, overall.level(), triggerType, overall.uncoveredCount());

        Integer capacityTypeId = safetyCheckRepository.findDurTypeIdByCode("CPCTY_ATENT");
        for (OverdoseRow od : viewRepository.findOverdose(userId)) {
            // MedilightService.buildTotals()와 같은 어휘 — RED면 상한 초과, medCount>=2면 중복,
            // 그 외(YELLOW인데 아직 상한 근접)는 상한 근접으로 본다.
            String reasonCode = "RED".equals(od.level())
                    ? "OVER_LIMIT"
                    : (od.medCount() != null && od.medCount() >= 2 ? "DUPLICATE" : "NEAR_LIMIT");
            safetyCheckRepository.insertItem(checkId, capacityTypeId, od.ingredientId(),
                    null, null, od.totalDaily(), od.maxQty(), od.level(), reasonCode);
        }

        for (SingleConflictRow sc : viewRepository.findSingleConflicts(userId)) {
            safetyCheckRepository.insertItem(checkId, sc.durTypeId(), sc.ingredientId(),
                    sc.medicationId(), null, null, null, sc.level(), "SINGLE_RULE");
        }

        Integer noDurDataTypeId = safetyCheckRepository.findNoDurDataTypeId();
        for (UncoveredRow uc : viewRepository.findUncoveredIngredients(userId)) {
            safetyCheckRepository.insertItem(checkId, noDurDataTypeId, uc.ingredientId(),
                    null, null, null, null, "INFO", "NO_DUR_DATA");
        }
    }
}
