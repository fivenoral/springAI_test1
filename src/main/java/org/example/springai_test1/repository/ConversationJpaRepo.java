package org.example.springai_test1.repository;

import org.example.springai_test1.model.Conversation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 会话 JPA 仓储接口
 */
public interface ConversationJpaRepo extends JpaRepository<Conversation, String> {

    /** 按模型查询会话列表（按创建时间升序） */
    List<Conversation> findByModelOrderByCreatedAtAsc(String model);

    /** 按模型删除所有会话 */
    void deleteByModel(String model);
}
