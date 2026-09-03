package com.sykessec.calendarsync.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * One shared RestClient.Builder for every outbound HTTP call this app
 * makes (hand-rolled CalDAV, and Stage 1/2 OAuth token exchanges) - a
 * fresh RestClient is built per call site off this builder so each caller
 * can layer its own base URL / auth without mutating shared state.
 */
@Configuration
public class WebClientConfig {

    @Bean
    public RestClient.Builder restClientBuilder() {
        return RestClient.builder();
    }
}
