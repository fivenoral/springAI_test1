package org.example.springai_test1.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 聊天消息历史实体（JPA），映射到 BSG_CHAT_MESSAGE_HIS 表
 * 用于存储历史消息记录，清除对话时只逻辑删除
 */
@Entity
@Table(name = "BSG_CHAT_MESSAGE_HIS")
public class ChatMessageHis {

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

    /** 所属会话ID（外键） */
    @Column(name = "conversation_id", nullable = false)
    private String conversationId;

    /** 图片URL（可选，用于视觉模型） */
    @Column(name = "image_url")
    private String imageUrl;

    /** 有效标识：1-有效，0-无效 */
    @Column(name = "valid_flag", nullable = false)
    private Integer validFlag;

    /** 创建时间 */
    @Column(name = "create_time", nullable = false)
    private LocalDateTime createTime;

    /** 更新时间 */
    @Column(name = "update_time")
    private LocalDateTime updateTime;

    public ChatMessageHis() {
        this.validFlag = 1;
        this.createTime = LocalDateTime.now();
        this.updateTime = LocalDateTime.now();
    }

    public ChatMessageHis(String role, String content, String model, String conversationId) {
        this.role = role;
        this.content = content;
        this.model = model;
        this.conversationId = conversationId;
        this.validFlag = 1;
        this.createTime = LocalDateTime.now();
        this.updateTime = LocalDateTime.now();
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

    public String getConversationId() {
        return conversationId;
    }

    public void setConversationId(String conversationId) {
        this.conversationId = conversationId;
    }

    public Integer getValidFlag() {
        return validFlag;
    }

    public void setValidFlag(Integer validFlag) {
        this.validFlag = validFlag;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }

    public LocalDateTime getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(LocalDateTime updateTime) {
        this.updateTime = updateTime;
    }

    public String getImageUrl() {
        return imageUrl;
    }

    public void setImageUrl(String imageUrl) {
        this.imageUrl = imageUrl;
    }
}
