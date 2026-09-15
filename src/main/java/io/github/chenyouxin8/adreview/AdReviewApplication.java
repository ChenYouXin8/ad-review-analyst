package io.github.chenyouxin8.adreview;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * AI 投流复盘分析师 - 启动类
 *
 * 基于 Spring Boot + Spring AI + 巨量千川 MCP 的智能投放复盘系统
 */
@SpringBootApplication
@EnableScheduling
public class AdReviewApplication {

    public static void main(String[] args) {
        SpringApplication.run(AdReviewApplication.class, args);
    }
}
