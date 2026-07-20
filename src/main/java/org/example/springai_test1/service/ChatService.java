package org.example.springai_test1.service;

import org.example.springai_test1.dto.ChatResult;
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
     * @return 聊天结果（包含回复内容和可选的思考过程）
     */
    ChatResult chat(String model, String conversationId, String userMessage);

    /**
     * 发送消息并获取 AI 回复（带上下文和思考模式控制）
     *
     * @param model          模型标识：openai / deepseek / qwen
     * @param conversationId 会话ID（为空则自动创建新会话）
     * @param userMessage    用户消息内容
     * @param thinkingMode   是否启用深度思考模式（仅 DeepSeek 支持）
     * @return 聊天结果（包含回复内容和可选的思考过程）
     */
    ChatResult chat(String model, String conversationId, String userMessage, boolean thinkingMode);

    /**
     * 生成图片并保存到本地
     *
     * @param prompt         图片生成提示词
     * @param conversationId 会话ID（为空则自动创建新会话）
     * @param size           图片尺寸，如 "1024*1024"、"720*1280"、"1280*720"
     * @return 生成的图片URL
     */
    String generateImage(String prompt, String conversationId, String size);

    /**
     * 编辑图片并保存到本地
     *
     * @param prompt         图片编辑提示词
     * @param conversationId 会话ID（为空则自动创建新会话）
     * @param imageData      原始图片字节数据
     * @param imageUrl       原始图片URL（用于存储）
     * @return 编辑后的图片URL
     */
    String editImage(String prompt, String conversationId, byte[] imageData, String imageUrl);

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
