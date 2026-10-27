package org.example.springai_test1.controller;

import org.example.springai_test1.dto.ChatMessageDTO;
import org.example.springai_test1.dto.ChatResult;
import org.example.springai_test1.dto.ConversationDTO;
import org.example.springai_test1.model.Conversation;
import org.example.springai_test1.repository.ConversationRepository;
import org.example.springai_test1.service.ChatService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.async.DeferredResult;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

/**
 * 聊天控制器 —— REST API（异步化版本）
 * <p>
 * 高并发优化：
 * <ul>
 *   <li>聊天/图像接口使用 {@link DeferredResult} + {@link CompletableFuture} 异步执行，
 *       Tomcat 线程立即释放，AI 调用在异步线程池（虚拟线程）中执行</li>
 *   <li>配合 {@code spring.mvc.async.request-timeout=120000} 设置异步超时</li>
 *   <li>并发能力从「200 个 Tomcat 线程 = 200 并发」提升到「数千并发」</li>
 * </ul>
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

    /** 异步任务执行器（虚拟线程），由 AsyncConfig 配置 */
    @Autowired
    @Qualifier("taskExecutor")
    private Executor taskExecutor;

    /**
     * 发送消息并获取 AI 回复（异步）
     * POST /api/chat/send
     * Body: { "model": "openai", "conversationId": "xxx", "message": "你好" }
     */
    @PostMapping("/send")
    public DeferredResult<ResponseEntity<?>> sendMessage(@RequestBody Map<String, String> request) {
        // 异步超时 120 秒（与 AI 调用最大耗时匹配）
        DeferredResult<ResponseEntity<?>> deferredResult = new DeferredResult<>(120_000L);

        String model = request.getOrDefault("model", "openai");
        String conversationId = request.get("conversationId");
        String message = request.get("message");
        boolean thinkingMode = "true".equalsIgnoreCase(request.get("thinkingMode"));

        logger.info("API调用 - 发送消息: 模型={}, 会话ID={}, 消息长度={}, 思考模式={}",
                model, conversationId, message != null ? message.length() : 0, thinkingMode);

        if (message == null || message.isBlank()) {
            logger.warn("API调用失败 - 消息内容为空");
            deferredResult.setResult(ResponseEntity.badRequest().body(Map.of("error", "消息内容不能为空")));
            return deferredResult;
        }

        // 异步执行：Tomcat 线程立即释放，AI 调用在虚拟线程中执行
        CompletableFuture.supplyAsync(() -> {
            ChatResult chatResult = chatService.chat(model, conversationId, message, thinkingMode);
            logger.info("API调用成功 - 发送消息完成: 模型={}, 回复长度={}, 有思考过程={}",
                    model, chatResult.reply().length(), chatResult.reasoningContent() != null);

            java.util.Map<String, Object> response = new java.util.HashMap<>();
            response.put("reply", chatResult.reply());
            response.put("model", model);
            if (chatResult.reasoningContent() != null) {
                response.put("reasoningContent", chatResult.reasoningContent());
            }
            return ResponseEntity.ok((Object) response);
        }, taskExecutor).whenComplete((response, throwable) -> {
            if (throwable != null) {
                logger.error("聊天请求失败 - 模型={}, 会话ID={}", model, conversationId, throwable);
                deferredResult.setResult(ResponseEntity.internalServerError()
                        .body(Map.of("error", "聊天请求失败: " + throwable.getMessage())));
            } else {
                deferredResult.setResult(response);
            }
        });

        // 异步超时回调
        deferredResult.onTimeout(() -> {
            logger.warn("聊天请求超时 - 模型={}, 会话ID={}", model, conversationId);
            deferredResult.setResult(ResponseEntity.status(504)
                    .body(Map.of("error", "AI 响应超时，请稍后重试")));
        });

        return deferredResult;
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

    // ==================== 图片处理接口（异步） ====================

    /**
     * 生成图片（异步）
     * POST /api/chat/generate-image
     */
    @PostMapping("/generate-image")
    public DeferredResult<ResponseEntity<?>> generateImage(@RequestBody Map<String, String> request) {
        DeferredResult<ResponseEntity<?>> deferredResult = new DeferredResult<>(120_000L);

        String prompt = request.get("prompt");
        String conversationId = request.get("conversationId");
        String size = request.getOrDefault("size", "1024*1024");

        logger.info("API调用 - 生成图片: 提示词={}, 会话ID={}, 尺寸={}", prompt, conversationId, size);

        if (prompt == null || prompt.isBlank()) {
            deferredResult.setResult(ResponseEntity.badRequest().body(Map.of("error", "提示词不能为空")));
            return deferredResult;
        }

        CompletableFuture.supplyAsync(() -> {
            String imageUrl = chatService.generateImage(prompt, conversationId, size);
            logger.info("API调用成功 - 生成图片完成: 图片URL={}", imageUrl);
            return ResponseEntity.ok((Object) Map.of("imageUrl", imageUrl, "prompt", prompt));
        }, taskExecutor).whenComplete((response, throwable) -> {
            if (throwable != null) {
                logger.error("图片生成失败", throwable);
                deferredResult.setResult(ResponseEntity.internalServerError()
                        .body(Map.of("error", "图片生成失败: " + throwable.getMessage())));
            } else {
                deferredResult.setResult((ResponseEntity<?>) response);
            }
        });

        return deferredResult;
    }

    /**
     * 编辑图片（异步）
     * POST /api/chat/edit-image
     */
    @PostMapping(value = "/edit-image", consumes = "multipart/form-data")
    public DeferredResult<ResponseEntity<?>> editImage(
            @RequestParam(value = "prompt") String prompt,
            @RequestParam(value = "conversationId", required = false) String conversationId,
            @RequestParam("image") MultipartFile image) {

        DeferredResult<ResponseEntity<?>> deferredResult = new DeferredResult<>(120_000L);

        logger.info("API调用 - 编辑图片: 提示词={}, 会话ID={}, 文件名={}, 大小={}",
                prompt, conversationId, image.getOriginalFilename(), image.getSize());

        if (image.isEmpty()) {
            deferredResult.setResult(ResponseEntity.badRequest().body(Map.of("error", "图片不能为空")));
            return deferredResult;
        }

        if (prompt == null || prompt.isBlank()) {
            deferredResult.setResult(ResponseEntity.badRequest().body(Map.of("error", "编辑提示词不能为空")));
            return deferredResult;
        }

        // 同步保存原始图片（毫秒级 IO，无需异步）
        final String originalImageUrl;
        try {
            originalImageUrl = saveImage(image);
            logger.info("原始图片已保存, URL: {}", originalImageUrl);
        } catch (IOException e) {
            logger.error("图片保存失败", e);
            deferredResult.setResult(ResponseEntity.internalServerError()
                    .body(Map.of("error", "图片保存失败: " + e.getMessage())));
            return deferredResult;
        }

        // 异步执行图片编辑（AI 调用，耗时较长）
        final byte[] imageData;
        try {
            imageData = image.getBytes();
        } catch (IOException e) {
            deferredResult.setResult(ResponseEntity.internalServerError()
                    .body(Map.of("error", "读取图片数据失败: " + e.getMessage())));
            return deferredResult;
        }

        CompletableFuture.supplyAsync(() -> {
            String editedImageUrl = chatService.editImage(prompt, conversationId, imageData, originalImageUrl);
            return ResponseEntity.ok((Object) Map.of(
                    "editedImageUrl", editedImageUrl,
                    "originalImageUrl", originalImageUrl,
                    "prompt", prompt
            ));
        }, taskExecutor).whenComplete((response, throwable) -> {
            if (throwable != null) {
                logger.error("图片编辑失败", throwable);
                deferredResult.setResult(ResponseEntity.internalServerError()
                        .body(Map.of("error", "图片编辑失败: " + throwable.getMessage())));
            } else {
                deferredResult.setResult((ResponseEntity<?>) response);
            }
        });

        return deferredResult;
    }

    /**
     * 保存图片文件并返回可访问的URL
     */
    private String saveImage(MultipartFile image) throws IOException {
        Path uploadPath = Paths.get(uploadDir).toAbsolutePath();
        Files.createDirectories(uploadPath);

        String originalFilename = image.getOriginalFilename();
        String extension = "";
        if (originalFilename != null && originalFilename.contains(".")) {
            extension = originalFilename.substring(originalFilename.lastIndexOf("."));
        }
        String filename = UUID.randomUUID().toString() + extension;

        Path filePath = uploadPath.resolve(filename);
        Files.copy(image.getInputStream(), filePath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        logger.info("图片保存到: {}", filePath);

        return "/uploads/" + filename;
    }

    // ==================== Entity到DTO转换方法 ====================

    private ConversationDTO toDTOWithMessages(String conversationId, Conversation conversation) {
        ConversationDTO dto = new ConversationDTO();
        dto.setId(conversation.getId());
        dto.setModel(conversation.getModel());
        dto.setCreatedAt(conversation.getCreatedAt());

        List<org.example.springai_test1.model.ChatMessage> messages =
                conversationRepository.findMessagesByConversationId(conversationId);

        List<ChatMessageDTO> messageDTOs = messages.stream()
                .map(this::toMessageDTO)
                .collect(Collectors.toList());
        dto.setMessages(messageDTOs);

        return dto;
    }

    private ChatMessageDTO toMessageDTO(org.example.springai_test1.model.ChatMessage message) {
        return new ChatMessageDTO(
                message.getId(),
                message.getRole(),
                message.getContent(),
                message.getModel(),
                message.getImageUrl(),
                message.getReasoningContent(),
                message.getTimestamp()
        );
    }
}
