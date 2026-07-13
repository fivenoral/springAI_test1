package org.example.springai_test1.repository;

import org.example.springai_test1.model.ChatMessage;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 消息 JPA 仓储接口
 */
public interface ChatMessageJpaRepo extends JpaRepository<ChatMessage, Long> {

    /** 按会话删除所有消息 */
    void deleteByConversationId(String conversationId);
}
