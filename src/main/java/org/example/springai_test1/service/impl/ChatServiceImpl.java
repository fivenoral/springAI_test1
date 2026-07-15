package org.example.springai_test1.service.impl;

import jakarta.annotation.Resource;
import org.example.springai_test1.model.ChatMessage;
import org.example.springai_test1.model.ChatMessageHis;
import org.example.springai_test1.model.Conversation;
import org.example.springai_test1.repository.ConversationRepository;
import org.example.springai_test1.service.ChatService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.content.Media;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.MimeTypeUtils;

import java.net.URI;
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

    private static final Logger logger = LoggerFactory.getLogger(ChatServiceImpl.class);

    @Resource(name = "openAiChatClient")
    private ChatClient openAiChatClient;

    @Resource(name = "deepSeekChatClient")
    private ChatClient deepSeekChatClient;

    @Resource(name = "qwenChatClient")
    private ChatClient qwenChatClient;

    @Resource(name = "teacherChatClient")
    private ChatClient teacherChatClient;

    @Resource(name = "qwenVisionChatClient")
    private ChatClient qwenVisionChatClient;

    @Autowired
    private ConversationRepository conversationRepository;

    @Override
    public String chat(String model, String conversationId, String userMessage) {
        logger.info("收到聊天请求 - 模型: {}, 会话ID: {}, 用户消息: {}", model, conversationId, userMessage);

        // 1. 获取或创建会话
        final String convId;
        Conversation conversation;
        if (conversationId == null || conversationId.isBlank()) {
            convId = UUID.randomUUID().toString();
            conversation = new Conversation(convId, model);
            conversationRepository.save(conversation);
            logger.info("创建新会话 - 会话ID: {}, 模型: {}", convId, model);
        } else {
            convId = conversationId;
            conversation = conversationRepository.findById(convId)
                    .orElseGet(() -> {
                        Conversation newConv = new Conversation(convId, model);
                        conversationRepository.save(newConv);
                        logger.info("会话不存在，创建新会话 - 会话ID: {}, 模型: {}", convId, model);
                        return newConv;
                    });
        }

        // 2. 记录用户消息
        ChatMessage userMsg = new ChatMessage("user", userMessage, model, convId);
        conversationRepository.saveMessage(userMsg);
        // 同时保存到历史表
        ChatMessageHis userMsgHis = new ChatMessageHis("user", userMessage, model, convId);
        userMsgHis.setTimestamp(userMsg.getTimestamp());
        conversationRepository.saveMessageHis(userMsgHis);

        // 3. 构建上下文消息列表（用于传递给 AI 模型）
        List<Message> contextMessages = buildContextMessages(convId);

        // 4. 调用对应的 AI 模型（ReReadingAdvisor 会自动处理消息增强）
        ChatClient chatClient = getChatClient(model);
        String aiReply = chatClient.prompt()
                /*.system(s -> s.text("You are a helpful assistant."))*/     //系统元数据
                /*.user(u -> u.text("What's the weather like?")
                        .metadata("messageId", "msg-123"))*/     //用户元数据
                .messages(contextMessages)
                .user(u->u.text(userMessage)/*.media()  // 通过该方法使输入包含图片音频等*/)
                .call()
                .content();

        // 5. 记录 AI 回复
        ChatMessage assistantMsg = new ChatMessage("assistant", aiReply, model, convId);
        conversationRepository.saveMessage(assistantMsg);
        // 同时保存到历史表
        ChatMessageHis assistantMsgHis = new ChatMessageHis("assistant", aiReply, model, convId);
        assistantMsgHis.setTimestamp(assistantMsg.getTimestamp());
        conversationRepository.saveMessageHis(assistantMsgHis);

        logger.info("AI回复完成 - 会话ID: {}, 模型: {}, 回复长度: {}", convId, model, aiReply.length());
        return aiReply;
    }

    @Override
    public String chatWithImage(String model, String conversationId, String userMessage, String imageUrl) {
        logger.info("收到图片聊天请求 - 模型: {}, 会话ID: {}, 用户消息: {}, 图片URL: {}", model, conversationId, userMessage, imageUrl);

        // 1. 获取或创建会话
        final String convId;
        Conversation conversation;
        if (conversationId == null || conversationId.isBlank()) {
            convId = UUID.randomUUID().toString();
            conversation = new Conversation(convId, model);
            conversationRepository.save(conversation);
            logger.info("创建新会话 - 会话ID: {}, 模型: {}", convId, model);
        } else {
            convId = conversationId;
            conversation = conversationRepository.findById(convId)
                    .orElseGet(() -> {
                        Conversation newConv = new Conversation(convId, model);
                        conversationRepository.save(newConv);
                        logger.info("会话不存在，创建新会话 - 会话ID: {}, 模型: {}", convId, model);
                        return newConv;
                    });
        }

        // 2. 记录用户消息（含图片URL）
        ChatMessage userMsg = new ChatMessage("user", userMessage, model, convId);
        userMsg.setImageUrl(imageUrl);
        conversationRepository.saveMessage(userMsg);
        ChatMessageHis userMsgHis = new ChatMessageHis("user", userMessage, model, convId);
        userMsgHis.setTimestamp(userMsg.getTimestamp());
        userMsgHis.setImageUrl(imageUrl);
        conversationRepository.saveMessageHis(userMsgHis);

        // 3. 调用视觉模型（带图片的多模态请求）
        ChatClient chatClient = getChatClient(model);
        String aiReply = chatClient.prompt()
                .user(u -> {
                    u.text(userMessage != null && !userMessage.isBlank() ? userMessage : "请描述这张图片");
                    if (imageUrl != null && !imageUrl.isBlank()) {
                        try {
                            Media imageMedia = new Media(MimeTypeUtils.IMAGE_PNG, new URI(imageUrl));
                            u.media(imageMedia);
                        } catch (Exception e) {
                            logger.error("图片URL转换失败: {}", imageUrl, e);
                        }
                    }
                })
                .call()
                .content();

        // 4. 记录 AI 回复
        ChatMessage assistantMsg = new ChatMessage("assistant", aiReply, model, convId);
        conversationRepository.saveMessage(assistantMsg);
        ChatMessageHis assistantMsgHis = new ChatMessageHis("assistant", aiReply, model, convId);
        assistantMsgHis.setTimestamp(assistantMsg.getTimestamp());
        conversationRepository.saveMessageHis(assistantMsgHis);

        logger.info("图片AI回复完成 - 会话ID: {}, 模型: {}, 回复长度: {}", convId, model, aiReply.length());
        return aiReply;
    }

    @Override
    public Conversation getConversation(String conversationId) {
        Conversation conversation = conversationRepository.findById(conversationId)
                .orElseThrow(() -> new IllegalArgumentException("会话不存在: " + conversationId));
        // 手动加载消息
        List<ChatMessage> messages = conversationRepository.findMessagesByConversationId(conversationId);
        // 注意：Conversation实体不再有messages字段，这里需要特殊处理
        // 由于DTO层已经处理了消息组装，这里可以返回不带消息的Conversation
        // 或者创建一个包含消息的DTO
        return conversation;
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
        conversationRepository.findById(conversationId)
                .orElseThrow(() -> new IllegalArgumentException("会话不存在: " + conversationId));
        conversationRepository.clearMessages(conversationId);
        logger.info("清除对话消息 - 会话ID: {}", conversationId);
    }

    @Override
    public void deleteConversation(String conversationId) {
        conversationRepository.deleteById(conversationId);
        logger.info("删除对话 - 会话ID: {}", conversationId);
    }

    @Override
    public void clearModelConversations(String model) {
        conversationRepository.deleteByModel(model);
        logger.info("清除模型所有对话 - 模型: {}", model);
    }

    @Override
    public void clearAll() {
        conversationRepository.deleteAll();
        logger.info("清除所有对话");
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
            case "teacher" -> teacherChatClient;
            case "qwen-vision" -> qwenVisionChatClient;
            default -> throw new IllegalArgumentException("不支持的模型: " + model
                    + "，支持的模型: openai, deepseek, qwen, teacher, qwen-vision");
        };
    }

    /**
     * 从会话历史构建 Spring AI 的 Message 列表（上下文）
     * 将 ChatMessage 转换为 Spring AI 的 Message 接口实现
     */
    private List<Message> buildContextMessages(String conversationId) {
        List<Message> messages = new ArrayList<>();
        List<ChatMessage> chatMessages = conversationRepository.findMessagesByConversationId(conversationId);
        for (ChatMessage msg : chatMessages) {
            switch (msg.getRole()) {
                case "user" -> messages.add(new UserMessage(msg.getContent()));
                case "assistant" -> messages.add(new AssistantMessage(msg.getContent()));
                // system 消息可根据需要扩展
            }
        }
        return messages;
    }
}
