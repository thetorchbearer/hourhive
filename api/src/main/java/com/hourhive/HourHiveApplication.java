package com.hourhive;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class HourHiveApplication {
    public static void main(String[] args) {
        SpringApplication.run(HourHiveApplication.class, args);
    }
}
