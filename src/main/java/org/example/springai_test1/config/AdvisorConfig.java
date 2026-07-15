package org.example.springai_test1.config;

import org.example.springai_test1.advisor.ReReadingAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Advisor 配置类
 * <p>
 * 集中管理各种 Advisor 的 Bean 定义
 */
@Configuration
public class AdvisorConfig {

    /**
     * SimpleLoggerAdvisor - 自动记录AI调用日志
     */
    @Bean
    public SimpleLoggerAdvisor simpleLoggerAdvisor() {
        return new SimpleLoggerAdvisor();
    }

    /**
     * ReReadingAdvisor - 重读顾问，让模型多次阅读输入以提升推理能力
     */
    @Bean
    public ReReadingAdvisor reReadingAdvisor() {
        return new ReReadingAdvisor();
    }
}
