package org.example.springai_test1.controller;

import org.example.springai_test1.model.Conversation;
import org.example.springai_test1.service.ChatService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 聊天控制器 —— REST API
 * <p>
 * 提供对话、会话列表、清除对话等接口。
 */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    /**
     * 发送消息并获取 AI 回复
     * POST /api/chat/send
     * Body: { "model": "openai", "conversationId": "xxx", "message": "你好" }
     */
    @PostMapping("/send")
    public ResponseEntity<Map<String, String>> sendMessage(@RequestBody Map<String, String> request) {
        String model = request.getOrDefault("model", "openai");
        String conversationId = request.get("conversationId");
        String message = request.get("message");

        if (message == null || message.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "消息内容不能为空"));
        }

        String reply = chatService.chat(model, conversationId, message);
        return ResponseEntity.ok(Map.of("reply", reply, "model", model));
    }

    /**
     * 获取指定会话的完整对话历史
     * GET /api/chat/conversation/{id}
     */
    @GetMapping("/conversation/{id}")
    public ResponseEntity<Conversation> getConversation(@PathVariable String id) {
        try {
            return ResponseEntity.ok(chatService.getConversation(id));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * 获取指定模型的会话列表
     * GET /api/chat/conversations?model=openai
     */
    @GetMapping("/conversations")
    public ResponseEntity<List<Conversation>> listConversations(
            @RequestParam(required = false) String model) {
        List<Conversation> conversations;
        if (model != null && !model.isBlank()) {
            conversations = chatService.listConversations(model);
        } else {
            conversations = chatService.listAllConversations();
        }
        return ResponseEntity.ok(conversations);
    }

    /**
     * 清除指定会话的对话记录（保留会话，清空消息）
     * DELETE /api/chat/conversation/{id}/messages
     */
    @DeleteMapping("/conversation/{id}/messages")
    public ResponseEntity<Map<String, String>> clearConversation(@PathVariable String id) {
        try {
            chatService.clearConversation(id);
            return ResponseEntity.ok(Map.of("message", "对话记录已清除"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * 删除指定会话
     * DELETE /api/chat/conversation/{id}
     */
    @DeleteMapping("/conversation/{id}")
    public ResponseEntity<Map<String, String>> deleteConversation(@PathVariable String id) {
        chatService.deleteConversation(id);
        return ResponseEntity.ok(Map.of("message", "会话已删除"));
    }

    /**
     * 清除指定模型的所有对话
     * DELETE /api/chat/model/{model}
     */
    @DeleteMapping("/model/{model}")
    public ResponseEntity<Map<String, String>> clearModelConversations(@PathVariable String model) {
        chatService.clearModelConversations(model);
        return ResponseEntity.ok(Map.of("message", model + " 模型的所有对话已清除"));
    }

    /**
     * 清除所有对话
     * DELETE /api/chat/all
     */
    @DeleteMapping("/all")
    public ResponseEntity<Map<String, String>> clearAll() {
        chatService.clearAll();
        return ResponseEntity.ok(Map.of("message", "所有对话已清除"));
    }
}
