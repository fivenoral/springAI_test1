package org.example.springai_test1.service.impl;

import jakarta.annotation.Resource;
import org.example.springai_test1.model.ChatMessage;
import org.example.springai_test1.model.Conversation;
import org.example.springai_test1.repository.ConversationRepository;
import org.example.springai_test1.service.ChatService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 聊天服务实现
 * <p>
 * 核心逻辑：
 * 1. 根据模型名称选择对应的 ChatClient Bean
 * 2. 从仓储层加载历史消息构建上下文
 * 3. 调用 AI 模型获取回复
 * 4. 将用户消息和 AI 回复持久化到仓储层
 */
@Service
@Transactional
public class ChatServiceImpl implements ChatService {

    @Resource(name = "openAiChatClient")
    private ChatClient openAiChatClient;

    @Resource(name = "deepSeekChatClient")
    private ChatClient deepSeekChatClient;

    @Resource(name = "qwenChatClient")
    private ChatClient qwenChatClient;

    @Autowired
    private ConversationRepository conversationRepository;

    @Override
    public String chat(String model, String conversationId, String userMessage) {
        // 1. 获取或创建会话
        final String convId;
        Conversation conversation;
        if (conversationId == null || conversationId.isBlank()) {
            convId = UUID.randomUUID().toString();
            conversation = new Conversation(convId, model);
        } else {
            convId = conversationId;
            conversation = conversationRepository.findById(convId)
                    .orElseGet(() -> new Conversation(convId, model));
        }

        // 2. 记录用户消息
        ChatMessage userMsg = new ChatMessage("user", userMessage, model);
        conversation.addMessage(userMsg);

        // 3. 构建上下文消息列表（用于传递给 AI 模型）
        List<Message> contextMessages = buildContextMessages(conversation);

        // 4. 调用对应的 AI 模型
        ChatClient chatClient = getChatClient(model);
        String aiReply = chatClient.prompt()
                /*.system(s -> s.text("You are a helpful assistant."))*/     //系统元数据
                /*.user(u -> u.text("What's the weather like?")
                        .metadata("messageId", "msg-123"))*/     //用户元数据
                .messages(contextMessages)
                .call()
                .content();

        // 5. 记录 AI 回复
        ChatMessage assistantMsg = new ChatMessage("assistant", aiReply, model);
        conversation.addMessage(assistantMsg);

        // 6. 持久化会话
        conversationRepository.save(conversation);

        return aiReply;
    }

    @Override
    public Conversation getConversation(String conversationId) {
        return conversationRepository.findById(conversationId)
                .orElseThrow(() -> new IllegalArgumentException("会话不存在: " + conversationId));
    }

    @Override
    public List<Conversation> listConversations(String model) {
        return conversationRepository.findByModel(model);
    }

    @Override
    public List<Conversation> listAllConversations() {
        return conversationRepository.findAll();
    }

    @Override
    public void clearConversation(String conversationId) {
        Conversation conversation = conversationRepository.findById(conversationId)
                .orElseThrow(() -> new IllegalArgumentException("会话不存在: " + conversationId));
        conversation.clearMessages();
        conversationRepository.save(conversation);
    }

    @Override
    public void deleteConversation(String conversationId) {
        conversationRepository.deleteById(conversationId);
    }

    @Override
    public void clearModelConversations(String model) {
        conversationRepository.deleteByModel(model);
    }

    @Override
    public void clearAll() {
        conversationRepository.deleteAll();
    }

    // ==================== 私有辅助方法 ====================

    /**
     * 根据模型名称获取对应的 ChatClient
     */
    private ChatClient getChatClient(String model) {
        return switch (model.toLowerCase()) {
            case "openai" -> openAiChatClient;
            case "deepseek" -> deepSeekChatClient;
            case "qwen" -> qwenChatClient;
            default -> throw new IllegalArgumentException("不支持的模型: " + model
                    + "，支持的模型: openai, deepseek, qwen");
        };
    }

    /**
     * 从会话历史构建 Spring AI 的 Message 列表（上下文）
     * 将 ChatMessage 转换为 Spring AI 的 Message 接口实现
     */
    private List<Message> buildContextMessages(Conversation conversation) {
        List<Message> messages = new ArrayList<>();
        for (ChatMessage msg : conversation.getMessages()) {
            switch (msg.getRole()) {
                case "user" -> messages.add(new UserMessage(msg.getContent()));
                case "assistant" -> messages.add(new AssistantMessage(msg.getContent()));
                // system 消息可根据需要扩展
            }
        }
        return messages;
    }
}
