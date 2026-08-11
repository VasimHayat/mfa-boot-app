package com.example.mfaapp.config;

import org.apache.coyote.http11.Http11Nio2Protocol;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Switches Tomcat from its default NIO connector to NIO2.
 *
 * <p>Tomcat's NIO poller calls {@link java.nio.channels.Selector#open()}, which on Windows is built
 * on an AF_UNIX loopback socket pair. Locked-down environments (sandboxes, some CI agents, hosts
 * with restrictive endpoint security) refuse that socket and the server fails to bind at all, with
 * an opaque "Unable to establish loopback connection". NIO2 uses IOCP instead and starts cleanly.
 *
 * <p>Inactive by default. Enable with {@code --spring.profiles.active=nio2} only if the default
 * connector cannot start.
 */
@Configuration
@Profile("nio2")
public class Nio2ConnectorConfig {

    @Bean
    public WebServerFactoryCustomizer<TomcatServletWebServerFactory> nio2Connector() {
        return factory -> factory.setProtocol(Http11Nio2Protocol.class.getName());
    }
}
