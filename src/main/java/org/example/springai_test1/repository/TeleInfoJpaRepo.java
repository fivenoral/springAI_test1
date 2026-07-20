package org.example.springai_test1.repository;

import org.example.springai_test1.model.TeleInfo;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * 通讯录 JPA 仓储接口
 */
public interface TeleInfoJpaRepo extends JpaRepository<TeleInfo, Long> {

    /**
     * 根据联系人姓名查找邮箱信息
     */
    Optional<TeleInfo> findByName(String name);
}
