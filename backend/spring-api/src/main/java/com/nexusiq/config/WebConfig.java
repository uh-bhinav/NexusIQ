package com.nexusiq.config;

import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Registers {@link SnakeCaseSortPageableResolver} ahead of Spring Data's own
 * default Pageable resolver — custom resolvers added here are tried first. */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final SnakeCaseSortPageableResolver snakeCaseSortPageableResolver;

    public WebConfig(SnakeCaseSortPageableResolver snakeCaseSortPageableResolver) {
        this.snakeCaseSortPageableResolver = snakeCaseSortPageableResolver;
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(snakeCaseSortPageableResolver);
    }
}
