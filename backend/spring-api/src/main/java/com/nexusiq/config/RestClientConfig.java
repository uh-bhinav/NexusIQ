package com.nexusiq.config;

import java.net.http.HttpClient;
import org.springframework.boot.restclient.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Applied by Boot's {@code RestClientAutoConfiguration} to every
 * autoconfigured {@link RestClient.Builder} before it's injected (here, into
 * {@code KnowledgeService} for calls to ai-service).
 *
 * <p>The JDK {@link HttpClient} Boot 4.1 uses by default attempts an h2c
 * (HTTP/2 cleartext) upgrade on every request: {@code Connection: Upgrade} +
 * {@code Transfer-Encoding: chunked}. uvicorn (ai-service) doesn't negotiate
 * that upgrade and silently never delivers the chunked body to the ASGI app —
 * every call failed with FastAPI's "body: Field required", confirmed
 * empirically by capturing the raw request on a throwaway TCP listener.
 * ai-service is HTTP/1.1-only, so there is nothing to gain from attempting
 * HTTP/2 here; pin the client to HTTP/1.1 so the upgrade is never attempted.
 */
@Configuration
public class RestClientConfig {

    @Bean
    public RestClientCustomizer http11RestClientCustomizer() {
        return builder -> builder.requestFactory(new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()));
    }
}
