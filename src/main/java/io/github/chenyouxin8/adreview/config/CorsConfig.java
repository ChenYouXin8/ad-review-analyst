package io.github.chenyouxin8.adreview.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 全局跨域配置。
 *
 * <p>允许来源从配置项 {@code ad-review.cors.allowed-origins} 读取（逗号分隔），
 * 默认 {@code *} 以兼容开发环境；生产环境可通过环境变量收紧白名单。</p>
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    @Value("${ad-review.cors.allowed-origins:*}")
    private String[] allowedOrigins = {"*"};

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowCredentials(true)
                .allowedOriginPatterns(allowedOrigins)
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .exposedHeaders("*");
    }
}
