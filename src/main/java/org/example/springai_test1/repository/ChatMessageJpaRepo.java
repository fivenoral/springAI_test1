package org.example.springai_test1.repository;

import org.example.springai_test1.model.ChatMessage;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 消息 JPA 仓储接口
 */
public interface ChatMessageJpaRepo extends JpaRepository<ChatMessage, Long> {

    /** 按会话删除所有消息 */
    void deleteByConversationId(String conversationId);

    /** 按会话ID查询所有消息（按时间升序，用于短对话全量加载） */
    List<ChatMessage> findByConversationIdOrderByTimestampAsc(String conversationId);

    /**
     * 按会话ID查询最近 N 条消息（按时间降序取 topN，用于上下文窗口裁剪）
     * <p>
     * 调用方拿到结果后通常需要再按时间升序翻转，以便按对话顺序送入 LLM。
     */
    List<ChatMessage> findTop100ByConversationIdOrderByTimestampDesc(String conversationId, Pageable pageable);
}
