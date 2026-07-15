package org.example.springai_test1.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 聊天消息实体（JPA），映射到 BSG_CHAT_MESSAGE 表
 */
@Entity
@Table(name = "BSG_CHAT_MESSAGE")
public class ChatMessage {

    /** 自增主键 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 消息角色：user / assistant / system */
    @Column(nullable = false)
    private String role;

    /** 消息内容 */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    /** 使用的模型名称 */
    @Column(nullable = false)
    private String model;

    /** 消息时间戳 */
    @Column(nullable = false)
    private LocalDateTime timestamp;

    /** 图片URL（可选，用于视觉模型） */
    @Column(name = "image_url")
    private String imageUrl;

    /** 所属会话ID（外键） */
    @Column(name = "conversation_id", nullable = false)
    private String conversationId;

    public ChatMessage() {
        this.timestamp = LocalDateTime.now();
    }

    public ChatMessage(String role, String content, String model) {
        this.role = role;
        this.content = content;
        this.model = model;
        this.timestamp = LocalDateTime.now();
    }

    public ChatMessage(String role, String content, String model, String conversationId) {
        this.role = role;
        this.content = content;
        this.model = model;
        this.conversationId = conversationId;
        this.timestamp = LocalDateTime.now();
    }

    // ==================== Getters & Setters ====================

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public LocalDateTime getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(LocalDateTime timestamp) {
        this.timestamp = timestamp;
    }

    public String getConversationId() {
        return conversationId;
    }

    public void setConversationId(String conversationId) {
        this.conversationId = conversationId;
    }

    public String getImageUrl() {
        return imageUrl;
    }

    public void setImageUrl(String imageUrl) {
        this.imageUrl = imageUrl;
    }
}
