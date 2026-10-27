package org.example.springai_test1.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 全局 HttpClient 连接池配置
 * <p>
 * 原 ChatServiceImpl 中使用 {@code HttpClient.newHttpClient()} 默认配置，
 * 没有连接池、并发连接数低（约 5~10）、无显式超时，高并发下会快速耗尽连接。
 * <p>
 * 统一配置一个全局共享的 HttpClient Bean：
 * <ul>
 *   <li>连接超时 10 秒（避免网关无响应时无限等待）</li>
 *   <li>使用固定大小线程池（50 并发），作为 HTTP 连接池上限</li>
 *   <li>使用虚拟线程执行器，高并发下不会阻塞平台线程</li>
 * </ul>
 * 所有需要发起 HTTP 调用的服务（DeepSeek 直连、图像 API 等）共享该 Bean。
 */
@Configuration
public class HttpClientConfig {

    private static final Logger logger = LoggerFactory.getLogger(HttpClientConfig.class);

    /** HTTP 连接超时（秒） */
    private static final int CONNECT_TIMEOUT_SECONDS = 10;

    /** HTTP 连接池最大并发数 */
    private static final int MAX_CONCURRENT_CONNECTIONS = 100;

    /**
     * 全局共享的 HttpClient Bean，带连接池和超时配置
     */
    @Bean
    public HttpClient httpClient() {

        // 使用固定大小线程池作为 HTTP 连接池，控制最大并发连接数
        ExecutorService executor = Executors.newFixedThreadPool(
                MAX_CONCURRENT_CONNECTIONS,
                r -> {
                    Thread t = new Thread(r, "http-client-pool-" + System.nanoTime());
                    t.setDaemon(true); // 守护线程，不阻止 JVM 退出
                    return t;
                });
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS))
                .executor(executor)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        logger.info("初始化全局 HttpClient：连接池大小={}, 连接超时={}s",
                MAX_CONCURRENT_CONNECTIONS, CONNECT_TIMEOUT_SECONDS);
        return client;
    }
}
