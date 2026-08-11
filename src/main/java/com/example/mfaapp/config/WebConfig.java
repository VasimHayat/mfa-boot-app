package com.example.mfaapp.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Deep-link support for the SPA: a path that is not an API call and carries no file extension is
 * forwarded to {@code /index.html} so Vue can route it client-side.
 *
 * <p>Forwarding rather than redirecting keeps the URL the user typed, which is what makes
 * {@code /modules/incident-response?category=SECURITY} survive a reload and a browser Back.
 *
 * <p>The {@code [^.]*} segments exclude anything with a dot, so real static files
 * ({@code /app.js}, {@code /vendor/vue.global.prod.js}) fall through to the resource handler. The
 * negative lookahead keeps a mistyped API path returning 404 instead of quietly serving HTML.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private static final String FORWARD = "forward:/index.html";
    private static final String SEGMENT = "{path:(?!api$|vendor$|components$)[^.]*}";
    private static final String SUB = "{sub:[^.]*}";
    private static final String LEAF = "{leaf:[^.]*}";

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addViewController("/").setViewName(FORWARD);
        registry.addViewController("/" + SEGMENT).setViewName(FORWARD);
        registry.addViewController("/" + SEGMENT + "/" + SUB).setViewName(FORWARD);
        registry.addViewController("/" + SEGMENT + "/" + SUB + "/" + LEAF).setViewName(FORWARD);
    }
}
