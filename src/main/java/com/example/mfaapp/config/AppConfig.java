package com.example.mfaapp.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
@EnableConfigurationProperties({MfaProperties.class, AppSecurityProperties.class})
public class AppConfig {

    /**
     * Every timestamp and TOTP time step in the app comes from this bean, never from
     * {@code Instant.now()} at the call site. Tests replace it with a fixed clock.
     */
    @Bean
    @ConditionalOnMissingBean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
