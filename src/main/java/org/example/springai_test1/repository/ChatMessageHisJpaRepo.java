package org.example.springai_test1.repository;

import org.example.springai_test1.model.ChatMessageHis;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 消息历史 JPA 仓储接口
 */
public interface ChatMessageHisJpaRepo extends JpaRepository<ChatMessageHis, Long> {

    /** 按会话ID逻辑删除所有消息（将valid_flag置为0） */
    @Modifying
    @Transactional
    @Query("UPDATE ChatMessageHis h SET h.validFlag = 0, h.updateTime = CURRENT_TIMESTAMP WHERE h.conversationId = :conversationId")
    void invalidateByConversationId(@Param("conversationId") String conversationId);

    /** 按会话ID查询有效消息 */
    List<ChatMessageHis> findByConversationIdAndValidFlagOrderByTimestampAsc(String conversationId, Integer validFlag);
}
