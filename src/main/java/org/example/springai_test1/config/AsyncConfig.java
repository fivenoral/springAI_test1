package org.example.springai_test1.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * 异步执行配置
 * <p>
 * 启用 Spring 的 {@code @Async} 支持，并配置全局异步任务执行器。
 * <p>
 * 高并发优化要点：
 * <ul>
 *   <li>使用自定义线程池替代 Tomcat 默认线程池，核心线程 50、最大 200，
 *       足以支撑数百个并发 AI 调用</li>
 *   <li>配合 {@code spring.mvc.async.request-timeout=120000} 实现 Controller 异步化，
 *       让 Tomcat 线程立即释放，AI 调用在异步线程池中执行</li>
 *   <li>使用守护线程，不阻止 JVM 正常退出</li>
 * </ul>
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    private static final Logger logger = LoggerFactory.getLogger(AsyncConfig.class);

    /** 核心线程数（常驻线程，处理日常并发） */
    private static final int CORE_POOL_SIZE = 50;

    /** 最大线程数（突发流量时扩展上限） */
    private static final int MAX_POOL_SIZE = 200;

    /** 队列容量（超过核心线程数的任务先进队列） */
    private static final int QUEUE_CAPACITY = 200;

    /** 空闲线程存活时间（秒），超过核心线程数的空闲线程会被回收 */
    private static final int KEEP_ALIVE_SECONDS = 60;

    /**
     * 全局异步任务执行器
     * <p>
     * 用于：
     * <ul>
     *   <li>@Async 标注的 Service 方法</li>
     *   <li>Controller 中 CompletableFuture 的异步执行</li>
     * </ul>
     * 采用 CallerRunsPolicy 拒绝策略：线程池满时由调用者线程执行，
     * 起到自然限流作用，避免任务丢失。
     */
    @Bean(name = "taskExecutor")
    public Executor taskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(CORE_POOL_SIZE);
        executor.setMaxPoolSize(MAX_POOL_SIZE);
        executor.setQueueCapacity(QUEUE_CAPACITY);
        executor.setKeepAliveSeconds(KEEP_ALIVE_SECONDS);
        executor.setThreadNamePrefix("ai-async-");
        executor.setDaemon(true);
        // 拒绝策略：由调用者线程执行，起到自然限流作用
        executor.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        logger.info("初始化异步任务执行器：核心线程={}, 最大线程={}, 队列容量={}",
                CORE_POOL_SIZE, MAX_POOL_SIZE, QUEUE_CAPACITY);
        return executor;
    }
}
