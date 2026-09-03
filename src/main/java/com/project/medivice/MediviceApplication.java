package com.project.medivice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableAsync;

// EnableAsync: OcrService.processAsync가 @Async로 별도 스레드에서 돈다(비동기 OCR 파이프라인).
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableAsync
public class MediviceApplication {

	public static void main(String[] args) {
		SpringApplication.run(MediviceApplication.class, args);
	}

}
