package com.ripsim.sift.config;

import java.util.List;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

@Configuration
public class CorsConfig {

    @Bean
    FilterRegistrationBean<CorsFilter> corsFilter(CorsProperties props) {
        CorsConfiguration api = new CorsConfiguration();
        api.setAllowedOrigins(props.allowedOrigins());
        api.setAllowedMethods(List.of("GET", "POST", "OPTIONS"));
        api.setAllowedHeaders(List.of("Content-Type"));
        api.setAllowCredentials(false);
        api.setMaxAge(props.maxAge());

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/v1/**", api);
        source.registerCorsConfiguration("/actuator/health/**", api); // so the dashboard can show "backend down"

        FilterRegistrationBean<CorsFilter> registration = new FilterRegistrationBean<>(new CorsFilter(source));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }
}
