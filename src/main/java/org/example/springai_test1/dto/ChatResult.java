package org.example.springai_test1.dto;

/**
 * 聊天结果 DTO - 包含 AI 回复内容和可选的思考过程
 */
public record ChatResult(String reply, String reasoningContent) {
}
