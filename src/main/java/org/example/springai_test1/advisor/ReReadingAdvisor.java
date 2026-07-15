package org.example.springai_test1.advisor;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.prompt.PromptTemplate;

import java.util.Map;

/**
 * 重读顾问 - 让模型多次阅读输入以提升推理能力
 * 通过在用户消息前添加重读提示，引导模型进行多次思考
 * 实现 BaseAdvisor 接口，符合 Spring AI 2.0 规范
 */
public class ReReadingAdvisor implements BaseAdvisor {

    private static final String DEFAULT_RE2_ADVISE_TEMPLATE = """
            请仔细阅读以下内容，理解其中的含义和逻辑关系：
            
            {re2_input_query}
            
            请重新阅读上述内容，思考是否有遗漏的信息或需要深入分析的地方：
            
            {re2_input_query}
            
            基于你的理解，请回答用户的问题或完成相应的任务。
            """;
    
    private final String re2AdviseTemplate;
    private int order = -100;

    public ReReadingAdvisor() {
        this(DEFAULT_RE2_ADVISE_TEMPLATE);
    }

    public ReReadingAdvisor(String re2AdviseTemplate) {
        this.re2AdviseTemplate = re2AdviseTemplate;
    }

    @Override
    public ChatClientRequest before(ChatClientRequest chatClientRequest, AdvisorChain advisorChain) {
        String augmentedUserText = PromptTemplate.builder()
                .template(this.re2AdviseTemplate)
                .variables(Map.of("re2_input_query", chatClientRequest.prompt().getUserMessage().getText()))
                .build()
                .render();
        return chatClientRequest.mutate()
                .prompt(chatClientRequest.prompt().augmentUserMessage(augmentedUserText))
                .build();
    }

    @Override
    public ChatClientResponse after(ChatClientResponse chatClientResponse, AdvisorChain advisorChain) {
        return chatClientResponse;
    }

    @Override
    public int getOrder() {
        return this.order;
    }

    public ReReadingAdvisor withOrder(int order) {
        this.order = order;
        return this;
    }
}
