package org.example.springai_test1.repository;

import org.example.springai_test1.model.ChatMessage;
import org.example.springai_test1.model.ChatMessageHis;
import org.example.springai_test1.model.Conversation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * 对话仓储层 —— 基于 MySQL 数据库（通过 JPA）
 * <p>
 * 委托给 Spring Data JPA 的 ConversationJpaRepo 实现持久化操作。
 * 公共 API 保持不变，服务层代码无需修改。
 */
@Repository("conversationRepository")
public class ConversationRepository {

    @Autowired
    private ConversationJpaRepo conversationJpaRepo;

    @Autowired
    private ChatMessageJpaRepo chatMessageJpaRepo;

    @Autowired
    private ChatMessageHisJpaRepo chatMessageHisJpaRepo;


    /**
     * 保存或更新对话
     */
    @Transactional
    public Conversation save(Conversation conversation) {
        return conversationJpaRepo.save(conversation);
    }

    /**
     * 根据 ID 查找对话
     */
    public Optional<Conversation> findById(String conversationId) {
        return conversationJpaRepo.findById(conversationId);
    }

    /**
     * 获取指定模型的所有对话列表
     */
    public List<Conversation> findByModel(String model) {
        return conversationJpaRepo.findByModelOrderByCreatedAtAsc(model);
    }

    /**
     * 获取所有对话列表
     */
    public List<Conversation> findAll() {
        return conversationJpaRepo.findAll();
    }

    /**
     * 删除指定对话（同时删除关联消息）
     */
    @Transactional
    public void deleteById(String conversationId) {
        chatMessageJpaRepo.deleteByConversationId(conversationId);
        conversationJpaRepo.deleteById(conversationId);
    }

    /**
     * 删除指定模型的所有对话
     */
    @Transactional
    public void deleteByModel(String model) {
        // 先查出该模型的所有会话，逐个删除消息
        List<Conversation> conversations = conversationJpaRepo.findByModelOrderByCreatedAtAsc(model);
        for (Conversation c : conversations) {
            chatMessageJpaRepo.deleteByConversationId(c.getId());
        }
        conversationJpaRepo.deleteByModel(model);
    }

    /**
     * 清除所有对话
     */
    @Transactional
    public void deleteAll() {
        chatMessageJpaRepo.deleteAll();
        conversationJpaRepo.deleteAll();
    }

    /**
     * 获取指定对话的所有消息
     */
    public List<ChatMessage> findMessagesByConversationId(String conversationId) {
        return chatMessageJpaRepo.findByConversationIdOrderByTimestampAsc(conversationId);
    }

    /**
     * 保存消息
     */
    @Transactional
    public ChatMessage saveMessage(ChatMessage message) {
        return chatMessageJpaRepo.save(message);
    }

    /**
     * 清除指定对话的所有消息
     */
    @Transactional
    public void clearMessages(String conversationId) {
        // 物理删除主表数据
        chatMessageJpaRepo.deleteByConversationId(conversationId);
        // 逻辑删除历史表数据
        chatMessageHisJpaRepo.invalidateByConversationId(conversationId);
    }

    /**
     * 保存历史消息
     */
    @Transactional
    public ChatMessageHis saveMessageHis(ChatMessageHis messageHis) {
        return chatMessageHisJpaRepo.save(messageHis);
    }
}
