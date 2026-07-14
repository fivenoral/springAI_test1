package org.example.springai_test1.dto;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 对话会话DTO - 用于API响应
 * 包含消息列表但不包含循环引用
 */
public class ConversationDTO {

    private String id;
    private String model;
    private List<ChatMessageDTO> messages;
    private LocalDateTime createdAt;

    public ConversationDTO() {
        this.messages = new ArrayList<>();
    }

    public ConversationDTO(String id, String model, LocalDateTime createdAt) {
        this.id = id;
        this.model = model;
        this.createdAt = createdAt;
        this.messages = new ArrayList<>();
    }

    // Getters and Setters

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

    public List<ChatMessageDTO> getMessages() {
        return messages;
    }

    public void setMessages(List<ChatMessageDTO> messages) {
        this.messages = messages;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
