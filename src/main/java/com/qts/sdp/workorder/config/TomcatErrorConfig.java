package com.qts.sdp.workorder.config;

import tools.jackson.databind.ObjectMapper;

import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

/** Makes Tomcat's own error responses use the API's problem format, see {@link ProblemErrorReportValve}. */
@Configuration
public class TomcatErrorConfig {

    /**
     * Runs after Spring Boot's own Tomcat customizers so this valve is the last one added to the
     * host pipeline. The last valve sees a finished request first, so it writes the body before
     * Tomcat's default HTML error valve gets the chance.
     */
    @Bean
    @Order(Ordered.LOWEST_PRECEDENCE)
    WebServerFactoryCustomizer<TomcatServletWebServerFactory> problemErrorReportValve(ObjectMapper objectMapper) {
        return factory -> factory.addContextCustomizers(
                context -> context.getParent().getPipeline().addValve(new ProblemErrorReportValve(objectMapper)));
    }
}
