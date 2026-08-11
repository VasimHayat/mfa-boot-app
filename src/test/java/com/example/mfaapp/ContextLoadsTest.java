package com.example.mfaapp;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/** Fails fast on wiring problems: bean graph, JPA metamodel and the security filter chain. */
@SpringBootTest
@ActiveProfiles("test")
class ContextLoadsTest {

    @Test
    void contextLoads() {
        // The assertion is that startup completed without throwing.
    }
}
