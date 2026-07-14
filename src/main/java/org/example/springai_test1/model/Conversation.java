package org.example.springai_test1.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 对话会话实体（JPA），映射到 BSG_CONVER_INFO 表
 */
@Entity
@Table(name = "BSG_CONVER_INFO")
public class Conversation {

    /** 会话唯一ID（主键） */
    @Id
    private String id;

    /** 使用的模型标识：openai / deepseek / qwen */
    @Column(nullable = false)
    private String model;

    /** 创建时间 */
    @Column(nullable = false)
    private LocalDateTime createdAt;

    public Conversation() {
        this.createdAt = LocalDateTime.now();
    }

    public Conversation(String id, String model) {
        this.id = id;
        this.model = model;
        this.createdAt = LocalDateTime.now();
    }

    // ==================== Getters & Setters ====================

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
