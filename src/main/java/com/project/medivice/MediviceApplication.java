package com.project.medivice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class MediviceApplication {

	public static void main(String[] args) {
		SpringApplication.run(MediviceApplication.class, args);
	}

}
