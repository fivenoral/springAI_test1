package org.example.springai_test1.dto;

import java.time.LocalDateTime;

/**
 * 聊天消息DTO - 用于API响应
 * 不包含循环引用，避免JSON序列化问题
 */
public class ChatMessageDTO {

    private Long id;
    private String role;
    private String content;
    private String model;
    private LocalDateTime timestamp;

    public ChatMessageDTO() {
    }

    public ChatMessageDTO(Long id, String role, String content, String model, LocalDateTime timestamp) {
        this.id = id;
        this.role = role;
        this.content = content;
        this.model = model;
        this.timestamp = timestamp;
    }

    // Getters and Setters

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
}
