package org.example.springai_test1.config;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Resilience4j 熔断 + 限流配置
 * <p>
 * 高并发保护机制：
 * <ul>
 *   <li><b>熔断器</b>：AI 网关连续失败 5 次 → 熔断 30 秒，期间快速失败，
 *       避免线程/连接堆积拖垮整个应用</li>
 *   <li><b>限流器</b>：限制每秒最多 30 个 AI 调用，保护下游 AI 网关不被打爆</li>
 * </ul>
 * 三个模型（OpenAI / DeepSeek / Qwen）共享同一套熔断限流配置，
 * 因为都走同一个电信网关 {@code aigw.telecomjs.com}。
 */
@Configuration
public class ResilienceConfig {

    private static final Logger logger = LoggerFactory.getLogger(ResilienceConfig.class);

    /** 连续失败次数阈值，达到后触发熔断 */
    private static final int FAILURE_THRESHOLD = 5;

    /** 熔断持续时间（半开状态前等待） */
    private static final Duration WAIT_DURATION_IN_OPEN_STATE = Duration.ofSeconds(30);

    /** 半开状态允许的试探请求数 */
    private static final int PERMITTED_NUMBER_OF_CALLS_IN_HALF_OPEN_STATE = 3;

    /** 限流刷新周期 */
    private static final Duration REFRESH_PERIOD = Duration.ofSeconds(1);

    /** 每个刷新周期内允许的最大请求数 */
    private static final int LIMIT_FOR_PERIOD = 30;

    /** 限流超时时间（获取不到许可时等待多久） */
    private static final Duration TIMEOUT_DURATION = Duration.ofSeconds(5);

    /**
     * AI 网关熔断器
     * <p>
     * 状态机：CLOSED → (连续失败 5 次) → OPEN → (30 秒后) → HALF_OPEN → (试探成功) → CLOSED
     */
    @Bean
    public CircuitBreaker aiCircuitBreaker() {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .failureRateThreshold(50)                    // 失败率阈值 50%
                .slowCallRateThreshold(80)                   // 慢调用率阈值 80%
                .slowCallDurationThreshold(Duration.ofSeconds(60)) // 慢调用判定：60 秒
                .waitDurationInOpenState(WAIT_DURATION_IN_OPEN_STATE)
                .permittedNumberOfCallsInHalfOpenState(PERMITTED_NUMBER_OF_CALLS_IN_HALF_OPEN_STATE)
                .minimumNumberOfCalls(10)                    // 至少 10 次调用后才计算失败率
                .slidingWindowSize(20)                       // 滑动窗口大小 20
                .build();
        CircuitBreaker circuitBreaker = CircuitBreakerRegistry.of(config)
                .circuitBreaker("aiGatewayCircuitBreaker", config);
        logger.info("初始化 AI 网关熔断器：失败阈值={}, 熔断时长={}s",
                FAILURE_THRESHOLD, WAIT_DURATION_IN_OPEN_STATE.getSeconds());
        return circuitBreaker;
    }

    /**
     * AI 网关限流器
     * <p>
     * 令牌桶算法：每秒补充 30 个许可，超出部分等待最多 5 秒，超时则拒绝。
     */
    @Bean
    public RateLimiter aiRateLimiter() {
        RateLimiterConfig config = RateLimiterConfig.custom()
                .limitRefreshPeriod(REFRESH_PERIOD)
                .limitForPeriod(LIMIT_FOR_PERIOD)
                .timeoutDuration(TIMEOUT_DURATION)
                .build();
        RateLimiter rateLimiter = RateLimiterRegistry.of(config)
                .rateLimiter("aiGatewayRateLimiter", config);
        logger.info("初始化 AI 网关限流器：{} 请求/{}s，超时={}s",
                LIMIT_FOR_PERIOD, REFRESH_PERIOD.getSeconds(), TIMEOUT_DURATION.getSeconds());
        return rateLimiter;
    }
}
