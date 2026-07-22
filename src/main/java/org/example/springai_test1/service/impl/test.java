/*
package org.example.springai_test1.service.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

*/
/**
 * Chroma 向量数据库 Demo
 * <p>
 * 说明：本项目的 application.properties 已经配置了 Chroma 自动装配
 * （spring.ai.vectorstore.chroma.* 与 spring.ai.openai.*），
 * Spring AI 会自动创建 {@link org.springframework.ai.embedding.EmbeddingModel}
 * 和 {@link VectorStore}（实际类型为 ChromaVectorStore）两个 Bean。
 * <p>
 * 因此这里无需再手动 new PgVectorStore / 定义 @Bean，直接注入自动装配好的
 * {@link VectorStore} 即可。CommandLineRunner 会在应用启动完成后执行一次 Demo。
 *//*

@Component
public class test implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(test.class);

    @Autowired
    private VectorStore vectorStore;

    @Override
    public void run(String... args) {
        log.info("===== Chroma 向量库 Demo 开始 =====");

        // 1. 准备文档
        List<Document> documents = List.of(
                new Document("Spring AI rocks!! Spring AI rocks!! Spring AI rocks!! Spring AI rocks!! Spring AI rocks!!",
                        Map.of("meta1", "meta1")),
                new Document("The World is Big and Salvation Lurks Around the Corner"),
                new Document("You walk forward facing the past and you turn back toward the future.",
                        Map.of("meta2", "meta2")));

        // 2. 写入向量库（内部会调用 EmbeddingModel 生成向量）
        log.info("正在写入 {} 条文档到向量库...", documents.size());
        vectorStore.add(documents);
        log.info("文档写入完成");

        // 3. 相似度检索
        log.info("正在执行相似度检索: query=\"Spring\", topK=5");
        List<Document> results = vectorStore.similaritySearch(
                SearchRequest.builder()
                        .query("Spring")
                        .topK(5)
                        .build());

        // 4. 打印结果
        int hitCount = (results == null) ? 0 : results.size();
        log.info("检索完成，命中 {} 条文档", hitCount);
        if (results != null) {
            results.forEach(doc ->
                    log.info("命中文档: {} | score={}", doc.getText(), doc.getScore()));
        }

        log.info("===== Chroma 向量库 Demo 结束 =====");
    }
}
*/
