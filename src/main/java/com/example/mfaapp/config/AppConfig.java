package com.example.mfaapp.config;

import com.example.mfaapp.service.SeaweedFsClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Clock;

@Configuration
@EnableConfigurationProperties({MfaProperties.class, AppSecurityProperties.class, StorageProperties.class})
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

    /**
     * A dedicated client for the object store, with its own timeouts. A request thread must not be
     * able to hang indefinitely because the filer stopped answering.
     */
    @Bean
    @ConditionalOnMissingBean
    public SeaweedFsClient seaweedFsClient(StorageProperties storageProperties) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(storageProperties.getConnectTimeout());
        requestFactory.setReadTimeout(storageProperties.getReadTimeout());

        RestClient restClient = RestClient.builder()
                .requestFactory(requestFactory)
                .build();
        return new SeaweedFsClient(restClient, storageProperties);
    }
}
