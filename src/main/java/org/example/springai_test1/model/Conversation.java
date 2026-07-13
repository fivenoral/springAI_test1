package org.example.springai_test1.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 对话会话实体（JPA），映射到 conversations 表
 */
@Entity
@Table(name = "conversations")
public class Conversation {

    /** 会话唯一ID（主键） */
    @Id
    private String id;

    /** 使用的模型标识：openai / deepseek / qwen */
    @Column(nullable = false)
    private String model;

    /** 消息列表（一对多，级联删除，孤儿删除） */
    @OneToMany(mappedBy = "conversation", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("timestamp ASC")
    private List<ChatMessage> messages = new ArrayList<>();

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

    /** 添加一条消息 */
    public void addMessage(ChatMessage message) {
        message.setConversation(this);
        this.messages.add(message);
    }

    /** 清除所有消息 */
    public void clearMessages() {
        this.messages.clear();
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

    public List<ChatMessage> getMessages() {
        return messages;
    }

    public void setMessages(List<ChatMessage> messages) {
        this.messages = messages;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
