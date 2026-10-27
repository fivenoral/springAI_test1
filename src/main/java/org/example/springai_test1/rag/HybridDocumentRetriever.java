package org.example.springai_test1.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 混合检索器（Hybrid Search）：并行执行稠密向量检索与 BM25 稀疏检索，
 * 再用 RRF（Reciprocal Rank Fusion，倒数排名融合）算法融合两路结果。
 * <p>
 * 高并发优化：
 * <ul>
 *   <li>向量检索和 BM25 检索通过 {@link CompletableFuture} 并行执行，
 *       总耗时从「向量 + BM25」降为「max(向量, BM25)」</li>
 *   <li>使用 ForkJoinPool.commonPool() 作为默认并行执行器</li>
 * </ul>
 * <p>
 * RRF 打分公式：{@code score(d) = Σ_i 1 / (k + rank_i(d))}，其中：
 * <ul>
 *   <li>{@code rank_i(d)} 是文档 d 在第 i 路检索结果中的名次（从 1 开始）；</li>
 *   <li>{@code k} 为平滑常数，业界惯例取 60，用于削弱头部名次的过度主导；</li>
 *   <li>同时命中两路的文档分数叠加，因此更容易进入最终 top-K。</li>
 * </ul>
 */
public class HybridDocumentRetriever implements DocumentRetriever {

    private static final Logger logger = LoggerFactory.getLogger(HybridDocumentRetriever.class);

    private final VectorStore vectorStore;
    private final Bm25IndexService bm25IndexService;

    /** 向量通道召回条数 */
    private final int vectorTopN;
    /** BM25 通道召回条数 */
    private final int bm25TopN;
    /** RRF 平滑常数 k */
    private final int rrfK;
    /** 融合后最终返回条数 */
    private final int finalTopK;
    /** 向量相似度阈值（0~1），<=0 表示不过滤 */
    private final double similarityThreshold;
    /** metadata 过滤表达式，空表示不过滤 */
    private final String filterExpression;

    public HybridDocumentRetriever(VectorStore vectorStore, Bm25IndexService bm25IndexService,
                                   int vectorTopN, int bm25TopN, int rrfK, int finalTopK,
                                   double similarityThreshold, String filterExpression) {
        this.vectorStore = vectorStore;
        this.bm25IndexService = bm25IndexService;
        this.vectorTopN = vectorTopN;
        this.bm25TopN = bm25TopN;
        this.rrfK = rrfK;
        this.finalTopK = finalTopK;
        this.similarityThreshold = similarityThreshold;
        this.filterExpression = filterExpression;
    }

    @Override
    public List<Document> retrieve(Query query) {
        String queryText = query.text();

        // ---------- 1. 并行执行向量检索 + BM25 检索（高并发优化核心） ----------
        CompletableFuture<List<Document>> vectorFuture = CompletableFuture.supplyAsync(() -> vectorSearch(queryText));
        CompletableFuture<List<Bm25IndexService.Bm25Hit>> bm25Future = CompletableFuture.supplyAsync(
                () -> bm25IndexService.search(queryText, bm25TopN));

        List<Document> vectorHits;
        List<Bm25IndexService.Bm25Hit> bm25Hits;
        try {
            // 等待两路检索都完成
            CompletableFuture.allOf(vectorFuture, bm25Future).join();
            vectorHits = vectorFuture.get();
            bm25Hits = bm25Future.get();
        } catch (Exception e) {
            logger.warn("混合检索并行执行异常，降级为空结果: {}", e.getMessage());
            return List.of();
        }

        // ---------- 2. RRF 融合 ----------
        Map<String, Document> docById = new LinkedHashMap<>();
        Map<String, Double> rrfScore = new LinkedHashMap<>();

        for (int rank = 0; rank < vectorHits.size(); rank++) {
            Document doc = vectorHits.get(rank);
            docById.putIfAbsent(doc.getId(), doc);
            accumulate(rrfScore, doc.getId(), rank);
        }
        for (int rank = 0; rank < bm25Hits.size(); rank++) {
            Bm25IndexService.Bm25Hit hit = bm25Hits.get(rank);
            docById.computeIfAbsent(hit.id(), id -> Document.builder()
                    .id(id)
                    .text(hit.content())
                    .build());
            accumulate(rrfScore, hit.id(), rank);
        }

        // ---------- 3. 按融合分降序取 top-K ----------
        List<Document> fused = rrfScore.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(finalTopK)
                .map(e -> docById.get(e.getKey()))
                .collect(ArrayList::new, ArrayList::add, ArrayList::addAll);
        
        logger.info("Hybrid 检索（并行）: 向量命中 {} 条，BM25 命中 {} 条，RRF 融合后返回 {} 条", 
                vectorHits.size(), bm25Hits.size(), fused.size());
                
        // 输出检索到的文档详情（用于调试）
        if (!fused.isEmpty()) {
            logger.info("===== 检索到的前 {} 个文档 =====", fused.size());
            for (int i = 0; i < Math.min(3, fused.size()); i++) {
                Document doc = fused.get(i);
                String preview = doc.getText().length() > 200 ? 
                        doc.getText().substring(0, 200) + "..." : doc.getText();
                logger.info("  [{}] ID: {}, 文本预览：{}", i + 1, doc.getId(), preview);
            }
        }
        logger.info("==============================");
        return fused;
    }

    /**
     * 执行向量检索
     */
    private List<Document> vectorSearch(String queryText) {
        SearchRequest.Builder builder = SearchRequest.builder()
                .query(queryText)
                .topK(vectorTopN);
        if (similarityThreshold > 0) {
            builder.similarityThreshold(similarityThreshold);
        }
        if (StringUtils.hasText(filterExpression)) {
            builder.filterExpression(filterExpression);
        }
        List<Document> hits = vectorStore.similaritySearch(builder.build());
        return hits != null ? hits : List.of();
    }

    /**
     * 累加 RRF 分数。rank 为 0 基名次，公式使用 1 基名次，故 +1。
     */
    private void accumulate(Map<String, Double> rrfScore, String id, int rank) {
        double contribution = 1.0 / (rrfK + rank + 1);
        rrfScore.merge(id, contribution, Double::sum);
    }
}
