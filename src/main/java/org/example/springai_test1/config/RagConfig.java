package org.example.springai_test1.config;

import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.template.st.StTemplateRenderer;
import org.springframework.ai.vectorstore.SearchRequest;
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
}
