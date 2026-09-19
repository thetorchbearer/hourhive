package com.hourhive.config;

import com.hourhive.security.AuthInterceptor;
import com.hourhive.security.RateLimitInterceptor;
import java.util.Arrays;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final AppProperties props;
    private final AuthInterceptor authInterceptor;
    private final RateLimitInterceptor rateLimitInterceptor;

    public WebConfig(AppProperties props, AuthInterceptor authInterceptor,
                     RateLimitInterceptor rateLimitInterceptor) {
        this.props = props;
        this.authInterceptor = authInterceptor;
        this.rateLimitInterceptor = rateLimitInterceptor;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(10);
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        String[] origins = Arrays.stream(props.allowedOrigins().split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toArray(String[]::new);
        registry.addMapping("/api/**")
                .allowedOriginPatterns(origins)
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .maxAge(3600);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(rateLimitInterceptor).addPathPatterns("/api/auth/**");
        registry.addInterceptor(authInterceptor).addPathPatterns("/api/**");
    }
}
