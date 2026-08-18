package org.example.springai_test1.config;

import org.example.springai_test1.rag.Bm25IndexService;
import org.example.springai_test1.rag.HybridDocumentRetriever;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.rag.generation.augmentation.ContextualQueryAugmenter;
import org.springframework.ai.template.st.StTemplateRenderer;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RagConfig {

    /** 每次检索返回的最相似文档数量 */
    @Value("${app.rag.top-k:4}")
    private int topK;

    /** 相似度阈值（0~1），低于该分数的文档会被过滤掉；设为 0 表示不过滤 */
    @Value("${app.rag.similarity-threshold:0.0}")
    private double similarityThreshold;

    /**
     * metadata 过滤表达式，例如 "type == 'faq' && year >= 2024"；留空则不过滤。
     * 语法见 Spring AI Filter.Expression。
     */
    @Value("${app.rag.filter-expression:}")
    private String filterExpression;

    /** Advisor 执行顺序，值越小越先执行 */
    @Value("${app.rag.advisor-order:0}")
    private int advisorOrder;

    // ==================== Hybrid Search（BM25 + RRF）参数 ====================

    /** 向量通道召回条数（融合前） */
    @Value("${app.rag.hybrid.vector-top-n:10}")
    private int hybridVectorTopN;

    /** BM25 通道召回条数（融合前） */
    @Value("${app.rag.hybrid.bm25-top-n:10}")
    private int hybridBm25TopN;

    /** RRF 平滑常数 k，业界惯例 60 */
    @Value("${app.rag.hybrid.rrf-k:60}")
    private int hybridRrfK;

    /** 融合后最终返回条数，默认取向量检索的 top-k */
    @Value("${app.rag.hybrid.final-top-k:${app.rag.top-k:4}}")
    private int hybridFinalTopK;

    @Bean
    public PromptTemplate customPromptTemplate() {
        return PromptTemplate.builder()
                .renderer(StTemplateRenderer.builder()
                        .startDelimiterToken('<')
                        .endDelimiterToken('>')
                        .build())
                .template("""
                        <query>
                        
                        下面是从知识库中检索到的参考资料（可能为空，也可能与问题无关）：
                        
                        ---------------------
                        <question_answer_context>
                        ---------------------
                        
                        回答要求：
                        
                        1. 如果上述参考资料与问题相关，请优先依据参考资料作答。
                        2. 如果参考资料为空或与问题无关，请基于你自身的知识和已注册的工具回答，不要因为缺少资料而拒答或回答“不知道”。
                        """)
                .build();
    }

    /**
     * QuestionAnswerAdvisor Bean，注入 VectorStore 和自定义 PromptTemplate。
     * <p>
     * 通过 SearchRequest 自定义检索行为：
     * - topK：返回最相似的前 K 条文档
     * - similarityThreshold：相似度阈值过滤
     * - filterExpression：基于 metadata 的过滤表达式（可选）
     * 通过 order 控制该 Advisor 在链中的执行顺序。
     */
    @Bean
    public QuestionAnswerAdvisor questionAnswerAdvisor(org.springframework.ai.vectorstore.VectorStore vectorStore,
                                                       PromptTemplate customPromptTemplate) {
        SearchRequest.Builder searchRequestBuilder = SearchRequest.builder()
                .topK(topK)
                .similarityThreshold(similarityThreshold);
        // 仅当配置了过滤表达式时才设置，避免传入空字符串导致解析异常
        if (filterExpression != null && !filterExpression.isBlank()) {
            searchRequestBuilder.filterExpression(filterExpression);
        }

        return QuestionAnswerAdvisor.builder(vectorStore)
                .promptTemplate(customPromptTemplate)
                .searchRequest(searchRequestBuilder.build())
                .order(advisorOrder)
                .build();
    }

    // ============================================================
    // Hybrid Search（稠密向量 + BM25 稀疏检索 + RRF 融合）
    // 以下为新的模块化 RAG 检索链，取代上面的 QuestionAnswerAdvisor。
    // QuestionAnswerAdvisor Bean 仍保留，便于日后随时切换回纯向量检索。
    // ============================================================

    /**
     * 混合检索器：并行跑向量检索与 BM25，再用 RRF 融合。
     */
    @Bean
    public HybridDocumentRetriever hybridDocumentRetriever(VectorStore vectorStore,
                                                           Bm25IndexService bm25IndexService) {
        return new HybridDocumentRetriever(vectorStore, bm25IndexService,
                hybridVectorTopN, hybridBm25TopN, hybridRrfK, hybridFinalTopK,
                similarityThreshold, filterExpression);
    }

    /**
     * 混合检索用的查询增强器：把检索到的资料拼进用户提问。
     * <p>
     * 使用默认 {@code {}} 占位符（{@link ContextualQueryAugmenter} 强制要求含 {@code {query}} 和
     * {@code {context}}）。{@code allowEmptyContext(true)} 保证在检索结果为空时直接放行原始问题，
     * 让模型基于自身知识/工具作答，而非回复“无法回答”。
     */
    @Bean
    public ContextualQueryAugmenter hybridQueryAugmenter() {
        PromptTemplate promptTemplate = new PromptTemplate("""
                下面是从知识库中检索到的参考资料（可能为空，也可能与问题无关）：

                ---------------------
                {context}
                ---------------------

                回答要求：

                1. 如果上述参考资料与问题相关，请优先依据参考资料作答。
                2. 如果参考资料为空或与问题无关，请基于你自身的知识和已注册的工具回答，不要因为缺少资料而拒答或回答“不知道”。

                问题：{query}
                """);
        return ContextualQueryAugmenter.builder()
                .promptTemplate(promptTemplate)
                .allowEmptyContext(true)
                .build();
    }

    /**
     * 模块化 RAG Advisor：挂载混合检索器 + 查询增强器。
     * 在 ChatClientConfig 中用它替换 questionAnswerAdvisor 即启用 Hybrid Search。
     */
    @Bean
    public RetrievalAugmentationAdvisor retrievalAugmentationAdvisor(HybridDocumentRetriever hybridDocumentRetriever,
                                                                     ContextualQueryAugmenter hybridQueryAugmenter) {
        return RetrievalAugmentationAdvisor.builder()
                .documentRetriever(hybridDocumentRetriever)
                .queryAugmenter(hybridQueryAugmenter)
                .order(advisorOrder)
                .build();
    }
}
