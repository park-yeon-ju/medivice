package com.project.medivice.controller;

import com.project.medivice.dto.MedilightDto;
import com.project.medivice.service.DemoUserResolver;
import com.project.medivice.service.MedilightService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 프론트 계약: GET /api/medilight → 200. */
@RestController
@RequestMapping("/api")
public class MedilightController {

    private final MedilightService medilightService;
    private final DemoUserResolver demoUserResolver;

    public MedilightController(MedilightService medilightService, DemoUserResolver demoUserResolver) {
        this.medilightService = medilightService;
        this.demoUserResolver = demoUserResolver;
    }

    @GetMapping("/medilight")
    public MedilightDto medilight() {
        return medilightService.build(demoUserResolver.resolveUserId());
    }
}
