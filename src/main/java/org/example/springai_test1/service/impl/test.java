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

/**
 * 向量库 Demo（Ollama nomic-embed-text 向量化 + Chroma 持久化向量库）
 * <p>
 * 说明：向量库改用持久化的 Chroma。ChromaVectorStore 由 Spring AI 自动装配，
 * 这里直接注入 {@link VectorStore} Bean 即可（底层实现即 ChromaVectorStore，
 * 数据持久化在远程 Chroma 服务，应用重启不丢）。
 * <p>
 * 向量化模型仍为 Ollama 的 nomic-embed-text —— 已在 application.properties 中通过
 * {@code spring.ai.model.embedding=ollama} 关闭 OpenAI embedding 自动装配，
 * 使容器内只保留唯一的 EmbeddingModel，供 ChromaVectorStore 唯一注入。
 * CommandLineRunner 会在应用启动完成后执行一次 Demo。
 */

@Component
public class test implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(test.class);

    // 直接注入自动装配的向量库 Bean（底层为 ChromaVectorStore，数据持久化在远程 Chroma）
    @Autowired
    private VectorStore vectorStore;

    @Override
    public void run(String... args) {
        log.info("===== 向量库 Demo 开始（Ollama nomic-embed-text + Chroma 持久化）=====");

        // 1. 准备文档（用固定 id，重复运行时 Chroma 按 id upsert，避免每次启动都累积重复数据）
        List<Document> documents = List.of(
                new Document("doc-1",
                        "Spring AI rocks!! Spring AI rocks!! Spring AI rocks!! Spring AI rocks!! Spring AI rocks!!",
                        Map.of("meta1", "meta1")),
                new Document("doc-2",
                        "The World is Big and Salvation Lurks Around the Corner", Map.of()),
                new Document("doc-3",
                        "You walk forward facing the past and you turn back toward the future.",
                        Map.of("meta2", "meta2")));

        // 2. 写入 Chroma（内部会调用 Ollama EmbeddingModel 生成向量并持久化）
        log.info("正在写入 {} 条文档到 Chroma...", documents.size());
        vectorStore.add(documents);
        log.info("文档写入完成");

        // 3. 相似度检索
        log.info("正在执行相似度检索: query=\"Spring\", topK=5");
        List<Document> results = vectorStore.similaritySearch(
                SearchRequest.builder()
                        .query("Spring")
                        .topK(5)
                        /*.similarityThreshold(0.5)*/
                        .build());

        // 4. 打印结果
        int hitCount = (results == null) ? 0 : results.size();
        log.info("检索完成，命中 {} 条文档", hitCount);
        if (results != null) {
            results.forEach(doc ->
                    log.info("命中文档: {} | score={}", doc.getText(), doc.getScore()));
        }

        for (Document doc : results) {
            // 假设 Document 对象中有 getScore() 方法
            System.out.println("得分: " + doc.getScore() + ", 内容: " + doc.getText());
        }

        log.info("===== 向量库 Demo 结束 =====");
    }
}
