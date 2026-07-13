package org.example.springai_test1.service;

import org.example.springai_test1.model.Conversation;

import java.util.List;

/**
 * 聊天服务接口
 * <p>
 * 定义三个模型的对话、上下文管理、清除对话等核心操作。
 */
public interface ChatService {

    /**
     * 发送消息并获取 AI 回复（带上下文）
     *
     * @param model          模型标识：openai / deepseek / qwen
     * @param conversationId 会话ID（为空则自动创建新会话）
     * @param userMessage    用户消息内容
     * @return AI 回复内容
     */
    String chat(String model, String conversationId, String userMessage);

    /**
     * 获取指定会话的完整对话历史
     */
    Conversation getConversation(String conversationId);

    /**
     * 获取指定模型的所有会话列表
     */
    List<Conversation> listConversations(String model);

    /**
     * 获取所有会话列表
     */
    List<Conversation> listAllConversations();

    /**
     * 清除指定会话的对话记录
     */
    void clearConversation(String conversationId);

    /**
     * 删除指定会话
     */
    void deleteConversation(String conversationId);

    /**
     * 清除指定模型的所有对话
     */
    void clearModelConversations(String model);

    /**
     * 清除所有对话
     */
    void clearAll();
}
