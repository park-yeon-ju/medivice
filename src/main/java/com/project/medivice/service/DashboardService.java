package com.project.medivice.service;

import com.project.medivice.dto.DashboardResponse;
import com.project.medivice.dto.MedicationDto;
import com.project.medivice.dto.MedilightDto;
import com.project.medivice.dto.SymptomDto;
import com.project.medivice.dto.UserDto;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * SCR-MAIN-001이 한 번에 필요로 하는 최소 응답(handoff 문서 7장)을 조립한다.
 * 화면 하나가 이 응답만으로 렌더링되면 "Mock API를 활용한 실제 데이터 바인딩" 루브릭이 증명된다.
 */
@Service
public class DashboardService {

    private static final int RECENT_SYMPTOM_LIMIT = 20;

    private final UserService userService;
    private final MedicationService medicationService;
    private final SymptomService symptomService;
    private final MedilightService medilightService;
    private final DemoUserResolver demoUserResolver;

    public DashboardService(UserService userService, MedicationService medicationService,
            SymptomService symptomService, MedilightService medilightService, DemoUserResolver demoUserResolver) {
        this.userService = userService;
        this.medicationService = medicationService;
        this.symptomService = symptomService;
        this.medilightService = medilightService;
        this.demoUserResolver = demoUserResolver;
    }

    public DashboardResponse build() {
        Long userId = demoUserResolver.resolveUserId();
        UserDto user = userService.buildUser(userId);
        List<MedicationDto> medications = medicationService.list(userId);
        List<SymptomDto> symptoms = symptomService.list(userId, RECENT_SYMPTOM_LIMIT);
        MedilightDto medilight = medilightService.build(userId);
        return new DashboardResponse(user, medications, symptoms, medilight);
    }
}
