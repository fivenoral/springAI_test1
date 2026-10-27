package org.example.springai_test1.service.impl;

import org.example.springai_test1.model.ChatMessage;
import org.example.springai_test1.model.ChatMessageHis;
import org.example.springai_test1.model.Conversation;
import org.example.springai_test1.repository.ChatMessageHisJpaRepo;
import org.example.springai_test1.repository.ChatMessageJpaRepo;
import org.example.springai_test1.repository.ConversationJpaRepo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 聊天相关的纯事务性操作服务（含 Caffeine 缓存）
 * <p>
 * 设计目的：将数据库操作从 {@link ChatServiceImpl} 中剥离，
 * 避免整个聊天流程（含 5~60 秒的 AI 调用）持有数据库连接。
 * <p>
 * 每个 public 方法都是独立短事务，毫秒级完成，调用方（ChatServiceImpl）
 * 在两次事务调用之间执行 AI 调用，期间不占用任何数据库连接。
 * <p>
 * 高并发优化：
 * <ul>
 *   <li>读操作通过 {@code @Cacheable} 走 Caffeine 本地缓存，降低 DB 压力</li>
 *
 *   <li>写操作通过 {@code @CacheEvict} 主动清除相关缓存，保证数据一致性</li>
 * </ul>
 */
@Service
public class ChatTransactionalService {

    @Autowired
    private ConversationJpaRepo conversationJpaRepo;

    @Autowired
    private ChatMessageJpaRepo chatMessageJpaRepo;

    @Autowired
    private ChatMessageHisJpaRepo chatMessageHisJpaRepo;


    // ==================== 会话管理 ====================

    @Transactional
    @Caching(
            put = @CachePut(value = "conversation", key = "#conversation.id"),
            evict = {
                    @CacheEvict(value = "conversationList", allEntries = true),
                    @CacheEvict(value = "recentMessages", allEntries = true)
            }
    )
    public Conversation saveConversation(Conversation conversation) {
        return conversationJpaRepo.save(conversation);
    }

    @Transactional(readOnly = true)
    @Cacheable(value = "conversation", key = "#conversationId", unless = "#result == null")
    public Optional<Conversation> findConversation(String conversationId) {
        return conversationJpaRepo.findById(conversationId);
    }

    // ==================== 消息保存 ====================

    /**
     * 保存用户消息到主表 + 历史表（短事务）
     */
    @Transactional
    @Caching(evict = {
            @CacheEvict(value = "conversation", key = "#userMsg.conversationId"),
            @CacheEvict(value = "conversationList", allEntries = true),
            @CacheEvict(value = "recentMessages", allEntries = true)
    })
    public void saveUserMessage(ChatMessage userMsg) {
        chatMessageJpaRepo.save(userMsg);
        ChatMessageHis his = new ChatMessageHis(userMsg.getRole(), userMsg.getContent(),
                userMsg.getModel(), userMsg.getConversationId());
        his.setTimestamp(userMsg.getTimestamp());
        chatMessageHisJpaRepo.save(his);
    }

    /**
     * 保存 AI 回复到主表 + 历史表（短事务）
     */
    @Transactional
    @Caching(evict = {
            @CacheEvict(value = "conversation", key = "#assistantMsg.conversationId"),
            @CacheEvict(value = "conversationList", allEntries = true),
            @CacheEvict(value = "recentMessages", allEntries = true)
    })
    public void saveAssistantMessage(ChatMessage assistantMsg) {
        chatMessageJpaRepo.save(assistantMsg);
        ChatMessageHis his = new ChatMessageHis(assistantMsg.getRole(), assistantMsg.getContent(),
                assistantMsg.getModel(), assistantMsg.getConversationId());
        his.setTimestamp(assistantMsg.getTimestamp());
        // reasoningContent 仅存主表，历史表无此字段
        if (assistantMsg.getImageUrl() != null) {
            his.setImageUrl(assistantMsg.getImageUrl());
        }
        chatMessageHisJpaRepo.save(his);
    }

    // ==================== 上下文加载 ====================

    /**
     * 获取指定会话的全部消息（按时间升序）
     */
    @Transactional(readOnly = true)
    @Cacheable(value = "recentMessages", key = "'all:' + #conversationId")
    public List<ChatMessage> findAllMessages(String conversationId) {
        return chatMessageJpaRepo.findByConversationIdOrderByTimestampAsc(conversationId);
    }

    /**
     * 获取指定会话的最近 N 条消息（按时间升序返回，便于直接送入 LLM）
     * <p>
     * 用于上下文窗口裁剪：长对话下只取最近 N 条，避免 token 数爆炸。
     */
    @Transactional(readOnly = true)
    @Cacheable(value = "recentMessages", key = "'recent:' + #conversationId + ':' + #limit")
    public List<ChatMessage> findRecentMessages(String conversationId, int limit) {
        List<ChatMessage> desc = chatMessageJpaRepo.findTop100ByConversationIdOrderByTimestampDesc(
                conversationId, PageRequest.of(0, limit));
        // 翻转为时间升序，保持对话顺序
        List<ChatMessage> asc = new ArrayList<>(desc);
        java.util.Collections.reverse(asc);
        return asc;
    }

    // ==================== 清理操作 ====================

    @Transactional
    @Caching(evict = {
            @CacheEvict(value = "conversation", key = "#conversationId"),
            @CacheEvict(value = "conversationList", allEntries = true),
            @CacheEvict(value = "recentMessages", allEntries = true)
    })
    public void clearConversation(String conversationId) {
        chatMessageJpaRepo.deleteByConversationId(conversationId);
        chatMessageHisJpaRepo.invalidateByConversationId(conversationId);
    }

    @Transactional
    @Caching(evict = {
            @CacheEvict(value = "conversation", key = "#conversationId"),
            @CacheEvict(value = "conversationList", allEntries = true),
            @CacheEvict(value = "recentMessages", allEntries = true)
    })
    public void deleteConversation(String conversationId) {
        chatMessageJpaRepo.deleteByConversationId(conversationId);
        conversationJpaRepo.deleteById(conversationId);
    }

    @Transactional
    @CacheEvict(value = {"conversation", "conversationList", "recentMessages"}, allEntries = true)
    public void deleteByModel(String model) {
        List<Conversation> conversations = conversationJpaRepo.findByModelOrderByCreatedAtAsc(model);
        for (Conversation c : conversations) {
            chatMessageJpaRepo.deleteByConversationId(c.getId());
        }
        conversationJpaRepo.deleteByModel(model);
    }

    @Transactional
    @CacheEvict(value = {"conversation", "conversationList", "recentMessages"}, allEntries = true)
    public void deleteAll() {
        chatMessageJpaRepo.deleteAll();
        conversationJpaRepo.deleteAll();
    }
}
