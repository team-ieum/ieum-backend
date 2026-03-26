package com.ieum.api.config;

import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.context.annotation.Configuration;

@Configuration
@AutoConfigurationPackage(basePackages = {"com.ieum.auth", "com.ieum.ai"})
public class JpaConfig {
}
