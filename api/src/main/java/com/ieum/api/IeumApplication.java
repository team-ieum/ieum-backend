package com.ieum.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication(scanBasePackages = "com.ieum")
public class IeumApplication {
    public static void main(String[] args) {
        SpringApplication.run(IeumApplication.class, args);
    }
}
