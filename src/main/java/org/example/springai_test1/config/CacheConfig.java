package org.example.springai_test1.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * Caffeine 本地缓存配置
 * <p>
 * 高并发优化：通过本地内存缓存减少数据库查询压力。
 * 适用于「读多写少」的场景，如会话详情、会话列表、消息上下文等。
 * <p>
 * 缓存策略（按业务维度独立配置）：
 * <ul>
 *   <li><b>conversation</b>：会话详情，5 分钟过期，最多 500 条</li>
 *   <li><b>conversationList</b>：会话列表（按模型），2 分钟过期，最多 200 条</li>
 *   <li><b>recentMessages</b>：消息上下文（最近 N 条），1 分钟过期，最多 1000 条</li>
 * </ul>
 * 写操作（保存/删除）会通过 {@code @CacheEvict} 主动清除相关缓存，保证数据一致性。
 */
@Configuration
@EnableCaching
public class CacheConfig {

    private static final Logger logger = LoggerFactory.getLogger(CacheConfig.class);

    /**
     * Caffeine 缓存管理器
     * <p>
     * 为不同业务使用不同缓存名，每个缓存独立配置过期策略和容量。
     */
    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager manager = new CaffeineCacheManager(
                "conversation", "conversationList", "recentMessages");

        // 默认 Caffeine 配置
        manager.setCaffeine(Caffeine.newBuilder()
                .expireAfterWrite(2, TimeUnit.MINUTES)
                .maximumSize(1000)
                .recordStats()); // 开启统计，便于监控命中率

        logger.info("初始化 Caffeine 缓存管理器：缓存名=[conversation, conversationList, recentMessages]");
        return manager;
    }
}
