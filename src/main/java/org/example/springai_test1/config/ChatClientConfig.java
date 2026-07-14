package org.example.springai_test1.config;

import com.openai.client.OpenAIClient;
import com.openai.client.OpenAIClientAsync;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.setup.OpenAiSetup;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * 多模型 ChatClient 配置类
 * <p>
 * 为 OpenAI、DeepSeek、通义千问（Qwen）三个模型分别定义独立的 ChatClient Bean。
 * 三个模型均通过 OpenAI 兼容协议接入，使用时通过 @Resource(name=...) 注入对应的 ChatClient。
 * <p>
 * Spring AI 2.0 使用官方 OpenAI Java SDK，通过 OpenAiSetup 工具类创建客户端实例。
 */
@Configuration
public class ChatClientConfig {

    /**
     * SimpleLoggerAdvisor - 自动记录AI调用日志
     */
    @Bean
    public SimpleLoggerAdvisor simpleLoggerAdvisor() {
        return new SimpleLoggerAdvisor();
    }

    // ==================== DeepSeek 配置 ====================

    @Value("${spring.ai.deepseek.base-url:https://api.deepseek.com}")
    private String deepSeekBaseUrl;

    @Value("${spring.ai.deepseek.api-key:}")
    private String deepSeekApiKey;

    @Value("${spring.ai.deepseek.chat.options.model:deepseek-chat}")
    private String deepSeekModel;

    // ==================== 通义千问 Qwen 配置 ====================

    @Value("${spring.ai.qwen.base-url:https://dashscope.aliyuncs.com/compatible-mode/v1}")
    private String qwenBaseUrl;

    @Value("${spring.ai.qwen.api-key:}")
    private String qwenApiKey;

    @Value("${spring.ai.qwen.chat.options.model:qwen-plus}")
    private String qwenModel;

    /**
     * OpenAI ChatClient
     * 使用自动注入的 OpenAiChatModel（由 spring-ai-starter-model-openai 自动配置）
     */
    @Bean(name = "openAiChatClient")
    public ChatClient openAiChatClient(OpenAiChatModel openAiChatModel, SimpleLoggerAdvisor simpleLoggerAdvisor) {
        return ChatClient.builder(openAiChatModel)
                .defaultAdvisors(simpleLoggerAdvisor)
                .build();
    }

    /**
     * DeepSeek ChatClient
     * DeepSeek 兼容 OpenAI 协议，通过 OpenAiSetup 创建指向 DeepSeek API 的客户端，
     * 手动构建独立的 OpenAiChatModel 实例，避免与 OpenAI 的自动配置冲突。
     */
    @Bean(name = "deepSeekChatClient")
    public ChatClient deepSeekChatClient(SimpleLoggerAdvisor simpleLoggerAdvisor) {
        OpenAIClient deepSeekClient = OpenAiSetup.setupSyncClient(
                deepSeekBaseUrl, deepSeekApiKey, null, null, null, null,
                false, false, deepSeekModel, Duration.ofSeconds(60), 0, null, null);
        OpenAIClientAsync deepSeekClientAsync = OpenAiSetup.setupAsyncClient(
                deepSeekBaseUrl, deepSeekApiKey, null, null, null, null,
                false, false, deepSeekModel, Duration.ofSeconds(60), 0, null, null);

        OpenAiChatModel chatModel = OpenAiChatModel.builder()
                .openAiClient(deepSeekClient)
                .openAiClientAsync(deepSeekClientAsync)
                .options(OpenAiChatOptions.builder()
                        .model(deepSeekModel)
                        .build())
                .build()
                ;

        return ChatClient.builder(chatModel)
                /*.defaultSystem("You are a helpful assistant.")*/   //系统元数据
                .defaultAdvisors(simpleLoggerAdvisor)
                .build();
    }

    /**
     * 通义千问（Qwen）ChatClient
     * 通过 DashScope 的 OpenAI 兼容接口接入，使用 OpenAiSetup 创建客户端。
     */
    @Bean(name = "qwenChatClient")
    public ChatClient qwenChatClient(SimpleLoggerAdvisor simpleLoggerAdvisor) {
        OpenAIClient qwenClient = OpenAiSetup.setupSyncClient(
                qwenBaseUrl, qwenApiKey, null, null, null, null,
                false, false, qwenModel, Duration.ofSeconds(60), 0, null, null);
        OpenAIClientAsync qwenClientAsync = OpenAiSetup.setupAsyncClient(
                qwenBaseUrl, qwenApiKey, null, null, null, null,
                false, false, qwenModel, Duration.ofSeconds(60), 0, null, null);

        OpenAiChatModel chatModel = OpenAiChatModel.builder()
                .openAiClient(qwenClient)
                .openAiClientAsync(qwenClientAsync)
                .options(OpenAiChatOptions.builder()
                        .model(qwenModel)
                        .build())
                .build();

        return ChatClient.builder(chatModel)
                .defaultAdvisors(simpleLoggerAdvisor)
                .build();
    }

    /**
     * 温柔老师 ChatClient
     * 基于DeepSeek模型，通过系统提示词让AI以温柔老师的风格说话
     */
    @Bean(name = "teacherChatClient")
    public ChatClient teacherChatClient(SimpleLoggerAdvisor simpleLoggerAdvisor) {
        OpenAIClient deepSeekClient = OpenAiSetup.setupSyncClient(
                qwenBaseUrl, qwenApiKey, null, null, null, null,
                false, false, qwenModel, Duration.ofSeconds(60), 0, null, null);
        OpenAIClientAsync deepSeekClientAsync = OpenAiSetup.setupAsyncClient(
                qwenBaseUrl, qwenApiKey, null, null, null, null,
                false, false, qwenModel, Duration.ofSeconds(60), 0, null, null);

        OpenAiChatModel chatModel = OpenAiChatModel.builder()
                .openAiClient(deepSeekClient)
                .openAiClientAsync(deepSeekClientAsync)
                .options(OpenAiChatOptions.builder()
                        .model(qwenModel)
                        .build())
                .build();

        return ChatClient.builder(chatModel)
                .defaultSystem("You are a gentle and caring teacher. You speak with warmth, patience, and encouragement. " +
                        "Address the student with kindness and understanding. Use phrases like 'Let me explain', " +
                        "'Don't worry', 'You're doing great', 'Take your time', 'I'm here to help'. " +
                        "Be supportive, positive, and nurturing. Make learning feel comfortable and enjoyable. " +
                        "When explaining concepts, break them down clearly and gently. Celebrate progress and " +
                        "offer constructive feedback with compassion.")
                .defaultAdvisors(simpleLoggerAdvisor)
                .build();
    }
}
