package org.example.springai_test1.controller;

import org.example.springai_test1.dto.ConversationDTO;
import org.example.springai_test1.model.Conversation;
import org.example.springai_test1.repository.ConversationRepository;
import org.example.springai_test1.service.ChatService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 聊天控制器 —— REST API
 * <p>
 * 提供对话、会话列表、清除对话等接口。
 */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private static final Logger logger = LoggerFactory.getLogger(ChatController.class);

    @Value("${app.upload.dir:uploads}")
    private String uploadDir;

    @Autowired
    private ChatService chatService;

    @Autowired
    private ConversationRepository conversationRepository;

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

        logger.info("API调用 - 发送消息: 模型={}, 会话ID={}, 消息长度={}", model, conversationId, message != null ? message.length() : 0);

        if (message == null || message.isBlank()) {
            logger.warn("API调用失败 - 消息内容为空");
            return ResponseEntity.badRequest().body(Map.of("error", "消息内容不能为空"));
        }

        try {
            String reply = chatService.chat(model, conversationId, message);
            logger.info("API调用成功 - 发送消息完成: 模型={}, 回复长度={}", model, reply.length());
            return ResponseEntity.ok(Map.of("reply", reply, "model", model));
        } catch (Exception e) {
            logger.error("聊天请求失败 - 模型={}, 会话ID={}", model, conversationId, e);
            return ResponseEntity.internalServerError().body(Map.of("error", "聊天请求失败: " + e.getMessage()));
        }
    }

    /**
     * 获取指定会话的完整对话历史
     * GET /api/chat/conversation/{id}
     */
    @GetMapping("/conversation/{id}")
    public ResponseEntity<ConversationDTO> getConversation(@PathVariable String id) {
        logger.info("API调用 - 获取对话详情: 会话ID={}", id);
        try {
            Conversation conversation = chatService.getConversation(id);
            return ResponseEntity.ok(toDTOWithMessages(id, conversation));
        } catch (IllegalArgumentException e) {
            logger.warn("API调用失败 - 对话不存在: 会话ID={}", id);
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * 获取指定模型的会话列表
     * GET /api/chat/conversations?model=openai
     */
    @GetMapping("/conversations")
    public ResponseEntity<List<ConversationDTO>> listConversations(
            @RequestParam(required = false) String model) {
        logger.info("API调用 - 获取对话列表: 模型={}", model);
        List<Conversation> conversations;
        if (model != null && !model.isBlank()) {
            conversations = chatService.listConversations(model);
        } else {
            conversations = chatService.listAllConversations();
        }
        List<ConversationDTO> dtoList = conversations.stream()
                .map(conv -> toDTOWithMessages(conv.getId(), conv))
                .collect(Collectors.toList());
        logger.info("API调用成功 - 获取对话列表完成: 数量={}", dtoList.size());
        return ResponseEntity.ok(dtoList);
    }

    /**
     * 清除指定会话的对话记录（保留会话，清空消息）
     * DELETE /api/chat/conversation/{id}/messages
     */
    @DeleteMapping("/conversation/{id}/messages")
    public ResponseEntity<Map<String, String>> clearConversation(@PathVariable String id) {
        logger.info("API调用 - 清除对话消息: 会话ID={}", id);
        try {
            chatService.clearConversation(id);
            return ResponseEntity.ok(Map.of("message", "对话记录已清除"));
        } catch (IllegalArgumentException e) {
            logger.warn("API调用失败 - 对话不存在: 会话ID={}", id);
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * 删除指定会话
     * DELETE /api/chat/conversation/{id}
     */
    @DeleteMapping("/conversation/{id}")
    public ResponseEntity<Map<String, String>> deleteConversation(@PathVariable String id) {
        logger.info("API调用 - 删除对话: 会话ID={}", id);
        chatService.deleteConversation(id);
        return ResponseEntity.ok(Map.of("message", "会话已删除"));
    }

    /**
     * 清除指定模型的所有对话
     * DELETE /api/chat/model/{model}
     */
    @DeleteMapping("/model/{model}")
    public ResponseEntity<Map<String, String>> clearModelConversations(@PathVariable String model) {
        logger.info("API调用 - 清除模型所有对话: 模型={}", model);
        chatService.clearModelConversations(model);
        return ResponseEntity.ok(Map.of("message", model + " 模型的所有对话已清除"));
    }

    /**
     * 清除所有对话
     * DELETE /api/chat/all
     */
    @DeleteMapping("/all")
    public ResponseEntity<Map<String, String>> clearAll() {
        logger.info("API调用 - 清除所有对话");
        chatService.clearAll();
        return ResponseEntity.ok(Map.of("message", "所有对话已清除"));
    }

    // ==================== 图片处理接口 ====================

    /**
     * 生成图片
     * POST /api/chat/generate-image
     * Body: { "prompt": "一只可爱的猫", "conversationId": "xxx" }
     */
    @PostMapping("/generate-image")
    public ResponseEntity<Map<String, String>> generateImage(@RequestBody Map<String, String> request) {
        String prompt = request.get("prompt");
        String conversationId = request.get("conversationId");
        String size = request.getOrDefault("size", "1024*1024");

        logger.info("API调用 - 生成图片: 提示词={}, 会话ID={}, 尺寸={}", prompt, conversationId, size);

        if (prompt == null || prompt.isBlank()) {
            logger.warn("API调用失败 - 提示词为空");
            return ResponseEntity.badRequest().body(Map.of("error", "提示词不能为空"));
        }

        try {
            String imageUrl = chatService.generateImage(prompt, conversationId, size);
            logger.info("API调用成功 - 生成图片完成: 图片URL={}", imageUrl);
            return ResponseEntity.ok(Map.of("imageUrl", imageUrl, "prompt", prompt));
        } catch (Exception e) {
            logger.error("图片生成失败", e);
            return ResponseEntity.internalServerError().body(Map.of("error", "图片生成失败: " + e.getMessage()));
        }
    }

    /**
     * 编辑图片
     * POST /api/chat/edit-image
     * Content-Type: multipart/form-data
     * Parameters: prompt, conversationId, image (file)
     */
    @PostMapping(value = "/edit-image", consumes = "multipart/form-data")
    public ResponseEntity<Map<String, String>> editImage(
            @RequestParam(value = "prompt") String prompt,
            @RequestParam(value = "conversationId", required = false) String conversationId,
            @RequestParam("image") MultipartFile image) {

        logger.info("API调用 - 编辑图片: 提示词={}, 会话ID={}, 文件名={}, 大小={}",
                prompt, conversationId, image.getOriginalFilename(), image.getSize());

        if (image.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "图片不能为空"));
        }

        if (prompt == null || prompt.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "编辑提示词不能为空"));
        }

        try {
            // 先保存图片文件（用于前端显示原始图片）
            String originalImageUrl = saveImage(image);
            logger.info("原始图片已保存, URL: {}", originalImageUrl);

            // 调用图片编辑服务
            String editedImageUrl = chatService.editImage(prompt, conversationId, image.getBytes(), originalImageUrl);

            return ResponseEntity.ok(Map.of(
                    "editedImageUrl", editedImageUrl,
                    "originalImageUrl", originalImageUrl,
                    "prompt", prompt
            ));
        } catch (IOException e) {
            logger.error("图片保存失败", e);
            return ResponseEntity.internalServerError().body(Map.of("error", "图片保存失败: " + e.getMessage()));
        } catch (Exception e) {
            logger.error("图片编辑失败", e);
            return ResponseEntity.internalServerError().body(Map.of("error", "图片编辑失败: " + e.getMessage()));
        }
    }

    /**
     * 保存图片文件并返回可访问的URL
     */
    private String saveImage(MultipartFile image) throws IOException {
        // 确保上传目录存在
        Path uploadPath = Paths.get(uploadDir).toAbsolutePath();
        Files.createDirectories(uploadPath);

        // 生成唯一文件名
        String originalFilename = image.getOriginalFilename();
        String extension = "";
        if (originalFilename != null && originalFilename.contains(".")) {
            extension = originalFilename.substring(originalFilename.lastIndexOf("."));
        }
        String filename = UUID.randomUUID().toString() + extension;

        // 保存文件（使用 Files.copy 避免 transferTo 临时文件路径问题）
        Path filePath = uploadPath.resolve(filename);
        Files.copy(image.getInputStream(), filePath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        logger.info("图片保存到: {}", filePath);

        // 返回可访问的URL（通过静态资源映射）
        return "/uploads/" + filename;
    }

    // ==================== Entity到DTO转换方法 ====================

    private ConversationDTO toDTOWithMessages(String conversationId, Conversation conversation) {
        ConversationDTO dto = new ConversationDTO();
        dto.setId(conversation.getId());
        dto.setModel(conversation.getModel());
        dto.setCreatedAt(conversation.getCreatedAt());

        // 从Repository获取消息
        List<org.example.springai_test1.model.ChatMessage> messages = 
            conversationRepository.findMessagesByConversationId(conversationId);

        List<org.example.springai_test1.dto.ChatMessageDTO> messageDTOs = messages.stream()
                .map(this::toMessageDTO)
                .collect(Collectors.toList());
        dto.setMessages(messageDTOs);

        return dto;
    }

    private org.example.springai_test1.dto.ChatMessageDTO toMessageDTO(org.example.springai_test1.model.ChatMessage message) {
        return new org.example.springai_test1.dto.ChatMessageDTO(
                message.getId(),
                message.getRole(),
                message.getContent(),
                message.getModel(),
                message.getImageUrl(),
                message.getTimestamp()
        );
    }
}
