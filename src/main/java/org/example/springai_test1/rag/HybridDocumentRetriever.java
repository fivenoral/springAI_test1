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

/**
 * 混合检索器（Hybrid Search）：并行执行稠密向量检索与 BM25 稀疏检索，
 * 再用 RRF（Reciprocal Rank Fusion，倒数排名融合）算法融合两路结果。
 * <p>
 * RRF 打分公式：{@code score(d) = Σ_i 1 / (k + rank_i(d))}，其中：
 * <ul>
 *   <li>{@code rank_i(d)} 是文档 d 在第 i 路检索结果中的名次（从 1 开始）；</li>
 *   <li>{@code k} 为平滑常数，业界惯例取 60，用于削弱头部名次的过度主导；</li>
 *   <li>同时命中两路的文档分数叠加，因此更容易进入最终 top-K。</li>
 * </ul>
 * 跨通道以 Document id 作为去重/合并键；向量通道命中的 Document 保留其原始 metadata 与 score，
 * 仅在 BM25 单独命中时才根据 Lucene 存储字段重建 Document。
 * <p>
 * 实现 Spring AI 模块化 RAG 的 {@link DocumentRetriever}，可直接挂到
 * {@code RetrievalAugmentationAdvisor} 上。
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

        // ---------- 1. 稠密向量通道 ----------
        List<Document> vectorHits = vectorSearch(queryText);

        // ---------- 2. BM25 稀疏通道 ----------
        List<Bm25IndexService.Bm25Hit> bm25Hits = bm25IndexService.search(queryText, bm25TopN);

        // ---------- 3. RRF 融合 ----------
        // 保存每个 id 对应的 Document（优先向量通道的实例，含 metadata/score）
        Map<String, Document> docById = new LinkedHashMap<>();
        // 累计每个 id 的 RRF 分数
        Map<String, Double> rrfScore = new LinkedHashMap<>();

        for (int rank = 0; rank < vectorHits.size(); rank++) {
            Document doc = vectorHits.get(rank);
            docById.putIfAbsent(doc.getId(), doc);
            accumulate(rrfScore, doc.getId(), rank);
        }
        for (int rank = 0; rank < bm25Hits.size(); rank++) {
            Bm25IndexService.Bm25Hit hit = bm25Hits.get(rank);
            // BM25 单独命中且向量通道没有时，用 Lucene 存储字段重建 Document
            docById.computeIfAbsent(hit.id(), id -> Document.builder()
                    .id(id)
                    .text(hit.content())
                    .build());
            accumulate(rrfScore, hit.id(), rank);
        }

        // ---------- 4. 按融合分降序取 top-K ----------
        List<Document> fused = rrfScore.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(finalTopK)
                .map(e -> docById.get(e.getKey()))
                .collect(ArrayList::new, ArrayList::add, ArrayList::addAll);

        logger.info("Hybrid 检索: 向量命中 {} 条, BM25 命中 {} 条, RRF 融合后返回 {} 条",
                vectorHits.size(), bm25Hits.size(), fused.size());
        return fused;
    }

    /**
     * 执行向量检索，复用原 QuestionAnswerAdvisor 的 SearchRequest 语义
     * （topK / similarityThreshold / filterExpression）。
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
