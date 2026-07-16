package org.example.springai_test1.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import org.example.springai_test1.model.ChatMessage;
import org.example.springai_test1.model.ChatMessageHis;
import org.example.springai_test1.model.Conversation;
import org.example.springai_test1.repository.ConversationRepository;
import org.example.springai_test1.service.ChatService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 聊天服务实现
 * <p>
 * 核心逻辑：
 * 1. 根据模型名称选择对应的 ChatClient Bean
 * 2. 从仓储层加载历史消息构建上下文
 * 3. 调用 AI 模型获取回复
 * 4. 将用户消息和 AI 回复持久化到仓储层
 */
@Service
@Transactional
public class ChatServiceImpl implements ChatService {

    private static final Logger logger = LoggerFactory.getLogger(ChatServiceImpl.class);

    @Value("${spring.ai.qwen.api-key:}")
    private String qwenApiKey;

    @Value("${spring.ai.qwen.base-url:https://aigw.telecomjs.com/v1}")
    private String qwenBaseUrl;

    @Value("${spring.ai.qwen.image.model:qwen-image-edit-max}")
    private String qwenImageModel;

    private final HttpClient imageHttpClient = HttpClient.newHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Resource(name = "openAiChatClient")
    private ChatClient openAiChatClient;

    @Resource(name = "deepSeekChatClient")
    private ChatClient deepSeekChatClient;

    @Resource(name = "qwenChatClient")
    private ChatClient qwenChatClient;


    @Autowired
    private ConversationRepository conversationRepository;

    @Override
    public String chat(String model, String conversationId, String userMessage) {
        logger.info("收到聊天请求 - 模型: {}, 会话ID: {}, 用户消息: {}", model, conversationId, userMessage);

        // 1. 获取或创建会话
        final String convId;
        Conversation conversation;
        if (conversationId == null || conversationId.isBlank()) {
            convId = UUID.randomUUID().toString();
            conversation = new Conversation(convId, model);
            conversationRepository.save(conversation);
            logger.info("创建新会话 - 会话ID: {}, 模型: {}", convId, model);
        } else {
            convId = conversationId;
            conversation = conversationRepository.findById(convId)
                    .orElseGet(() -> {
                        Conversation newConv = new Conversation(convId, model);
                        conversationRepository.save(newConv);
                        logger.info("会话不存在，创建新会话 - 会话ID: {}, 模型: {}", convId, model);
                        return newConv;
                    });
        }

        // 2. 记录用户消息
        ChatMessage userMsg = new ChatMessage("user", userMessage, model, convId);
        conversationRepository.saveMessage(userMsg);
        // 同时保存到历史表
        ChatMessageHis userMsgHis = new ChatMessageHis("user", userMessage, model, convId);
        userMsgHis.setTimestamp(userMsg.getTimestamp());
        conversationRepository.saveMessageHis(userMsgHis);

        // 3. 构建上下文消息列表（用于传递给 AI 模型）
        List<Message> contextMessages = buildContextMessages(convId);

        // 4. 调用对应的 AI 模型（ReReadingAdvisor 会自动处理消息增强）
        ChatClient chatClient = getChatClient(model);
        String aiReply = chatClient.prompt()
                /*.system(s -> s.text("You are a helpful assistant."))*/     //系统元数据
                /*.user(u -> u.text("What's the weather like?")
                        .metadata("messageId", "msg-123"))*/     //用户元数据
                .messages(contextMessages)
                .user(u->u.text(userMessage)/*.media()  // 通过该方法使输入包含图片音频等*/)
                .call()
                .content();

        // 5. 记录 AI 回复
        ChatMessage assistantMsg = new ChatMessage("assistant", aiReply, model, convId);
        conversationRepository.saveMessage(assistantMsg);
        // 同时保存到历史表
        ChatMessageHis assistantMsgHis = new ChatMessageHis("assistant", aiReply, model, convId);
        assistantMsgHis.setTimestamp(assistantMsg.getTimestamp());
        conversationRepository.saveMessageHis(assistantMsgHis);

        logger.info("AI回复完成 - 会话ID: {}, 模型: {}, 回复长度: {}", convId, model, aiReply.length());
        return aiReply;
    }

    @Override
    public String generateImage(String prompt, String conversationId, String size) {
        logger.info("收到图片生成请求 - 提示词: {}, 会话ID: {}, 尺寸: {}", prompt, conversationId, size);

        try {
            // 获取或创建会话
            final String convId;
            if (conversationId == null || conversationId.isBlank()) {
                convId = UUID.randomUUID().toString();
                Conversation conversation = new Conversation(convId, "qwen-image");
                conversationRepository.save(conversation);
                logger.info("创建新会话 - 会话ID: {}, 模型: qwen-image", convId);
            } else {
                convId = conversationId;
                conversationRepository.findById(convId)
                        .orElseGet(() -> {
                            Conversation newConv = new Conversation(convId, "qwen-image");
                            conversationRepository.save(newConv);
                            logger.info("会话不存在，创建新会话 - 会话ID: {}, 模型: qwen-image", convId);
                            return newConv;
                        });
            }

            // 记录用户消息
            ChatMessage userMsg = new ChatMessage("user", prompt, "qwen-image", convId);
            conversationRepository.saveMessage(userMsg);
            ChatMessageHis userMsgHis = new ChatMessageHis("user", prompt, "qwen-image", convId);
            userMsgHis.setTimestamp(userMsg.getTimestamp());
            conversationRepository.saveMessageHis(userMsgHis);

            // 调用电信网关图像生成 API
            String imageUrl = callImageGenerateApi(prompt, null, size);

            logger.info("图像生成API返回URL: {}", imageUrl);

            // 下载图片到本地
            String localImageUrl = downloadImageToLocal(imageUrl, convId);

            // 记录 AI 回复（生成的图片URL）
            ChatMessage assistantMsg = new ChatMessage("assistant", "图片已生成: " + localImageUrl, "qwen-image", convId);
            assistantMsg.setImageUrl(localImageUrl);
            conversationRepository.saveMessage(assistantMsg);
            ChatMessageHis assistantMsgHis = new ChatMessageHis("assistant", "图片已生成: " + localImageUrl, "qwen-image", convId);
            assistantMsgHis.setTimestamp(assistantMsg.getTimestamp());
            assistantMsgHis.setImageUrl(localImageUrl);
            conversationRepository.saveMessageHis(assistantMsgHis);

            logger.info("图片生成完成 - 会话ID: {}, 本地URL: {}", convId, localImageUrl);
            return localImageUrl;
        } catch (Exception e) {
            logger.error("图片生成失败", e);
            throw new RuntimeException("图片生成失败: " + e.getMessage(), e);
        }
    }

    @Override
    public String editImage(String prompt, String conversationId, byte[] imageData, String imageUrl) {
        logger.info("收到图片编辑请求 - 提示词: {}, 会话ID: {}, 原始图片URL: {}, 数据大小: {}", 
                prompt, conversationId, imageUrl, imageData != null ? imageData.length : 0);

        try {
            // 获取或创建会话
            final String convId;
            if (conversationId == null || conversationId.isBlank()) {
                convId = UUID.randomUUID().toString();
                Conversation conversation = new Conversation(convId, "qwen-image");
                conversationRepository.save(conversation);
                logger.info("创建新会话 - 会话ID: {}, 模型: qwen-image", convId);
            } else {
                convId = conversationId;
                conversationRepository.findById(convId)
                        .orElseGet(() -> {
                            Conversation newConv = new Conversation(convId, "qwen-image");
                            conversationRepository.save(newConv);
                            logger.info("会话不存在，创建新会话 - 会话ID: {}, 模型: qwen-image", convId);
                            return newConv;
                        });
            }

            // 记录用户消息（含原始图片URL）
            ChatMessage userMsg = new ChatMessage("user", prompt, "qwen-image", convId);
            userMsg.setImageUrl(imageUrl);
            conversationRepository.saveMessage(userMsg);
            ChatMessageHis userMsgHis = new ChatMessageHis("user", prompt, "qwen-image", convId);
            userMsgHis.setTimestamp(userMsg.getTimestamp());
            userMsgHis.setImageUrl(imageUrl);
            conversationRepository.saveMessageHis(userMsgHis);

            // 检测原图尺寸，编辑时保持与原图一致
            String imageSize = detectImageSize(imageData);
            logger.info("原图尺寸: {}", imageSize);

            // 增强编辑提示词，防止文字重影/模糊
            String enhancedPrompt = enhanceEditPrompt(prompt);

            // 调用电信网关图像编辑 API
            String editedImageUrl = callImageGenerateApi(enhancedPrompt, imageData, imageSize);

            logger.info("图像编辑API返回URL: {}", editedImageUrl);

            // 下载编辑后的图片到本地
            String localImageUrl = downloadImageToLocal(editedImageUrl, convId);

            // 记录 AI 回复（编辑后的图片URL）
            ChatMessage assistantMsg = new ChatMessage("assistant", "图片已编辑: " + localImageUrl, "qwen-image", convId);
            assistantMsg.setImageUrl(localImageUrl);
            conversationRepository.saveMessage(assistantMsg);
            ChatMessageHis assistantMsgHis = new ChatMessageHis("assistant", "图片已编辑: " + localImageUrl, "qwen-image", convId);
            assistantMsgHis.setTimestamp(assistantMsg.getTimestamp());
            assistantMsgHis.setImageUrl(localImageUrl);
            conversationRepository.saveMessageHis(assistantMsgHis);

            logger.info("图片编辑完成 - 会话ID: {}, 本地URL: {}", convId, localImageUrl);
            return localImageUrl;
        } catch (Exception e) {
            logger.error("图片编辑失败", e);
            throw new RuntimeException("图片编辑失败: " + e.getMessage(), e);
        }
    }

    /**
     * 下载图片到本地
     */
    private String downloadImageToLocal(String imageUrl, String conversationId) throws Exception {
        // 提取URL中的文件名或生成新文件名
        String filename = extractFilenameFromUrl(imageUrl);
        String extension = filename.contains(".") ? filename.substring(filename.lastIndexOf('.')) : ".png";
        
        // 生成唯一文件名
        String uniqueFilename = conversationId + "_" + UUID.randomUUID().toString() + extension;
        
        // 确保上传目录存在
        java.nio.file.Path uploadPath = java.nio.file.Paths.get("uploads").toAbsolutePath();
        java.nio.file.Files.createDirectories(uploadPath);
        
        // 下载图片
        java.net.URL url = new java.net.URL(imageUrl);
        java.io.InputStream inputStream = url.openStream();
        java.nio.file.Path outputPath = uploadPath.resolve(uniqueFilename);
        java.nio.file.Files.copy(inputStream, outputPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        inputStream.close();
        
        logger.info("图片已下载到: {}", outputPath);
        
        // 返回可访问的URL
        return "/uploads/" + uniqueFilename;
    }

    /**
     * 从URL中提取文件名
     */
    private String extractFilenameFromUrl(String url) {
        try {
            String path = new java.net.URL(url).getPath();
            String filename = path.substring(path.lastIndexOf('/') + 1);
            if (filename.isEmpty()) {
                return "generated_image.png";
            }
            return filename;
        } catch (Exception e) {
            return "generated_image.png";
        }
    }

    @Override
    public Conversation getConversation(String conversationId) {
        Conversation conversation = conversationRepository.findById(conversationId)
                .orElseThrow(() -> new IllegalArgumentException("会话不存在: " + conversationId));
        // 手动加载消息
        List<ChatMessage> messages = conversationRepository.findMessagesByConversationId(conversationId);
        // 注意：Conversation实体不再有messages字段，这里需要特殊处理
        // 由于DTO层已经处理了消息组装，这里可以返回不带消息的Conversation
        // 或者创建一个包含消息的DTO
        return conversation;
    }

    @Override
    public List<Conversation> listConversations(String model) {
        return conversationRepository.findByModel(model);
    }

    @Override
    public List<Conversation> listAllConversations() {
        return conversationRepository.findAll();
    }

    @Override
    public void clearConversation(String conversationId) {
        conversationRepository.findById(conversationId)
                .orElseThrow(() -> new IllegalArgumentException("会话不存在: " + conversationId));
        conversationRepository.clearMessages(conversationId);
        logger.info("清除对话消息 - 会话ID: {}", conversationId);
    }

    @Override
    public void deleteConversation(String conversationId) {
        conversationRepository.deleteById(conversationId);
        logger.info("删除对话 - 会话ID: {}", conversationId);
    }

    @Override
    public void clearModelConversations(String model) {
        conversationRepository.deleteByModel(model);
        logger.info("清除模型所有对话 - 模型: {}", model);
    }

    @Override
    public void clearAll() {
        conversationRepository.deleteAll();
        logger.info("清除所有对话");
    }

    // ==================== 私有辅助方法 ====================

    /**
     * 调用电信网关图像生成/编辑 API
     * POST {qwenBaseUrl}/images/generations
     *
     * @param prompt    提示词
     * @param imageData 原始图片字节数据（为 null 时表示纯生成，非编辑）
     * @return 生成的图片 URL
     */
    /**
     * 调用电信网关图像生成/编辑 API
     * POST {qwenBaseUrl}/images/generations
     *
     * @param prompt    提示词
     * @param imageData 原始图片字节数据（为 null 时表示纯生成，非编辑）
     * @param size      图片尺寸，如 "1024*1024"
     * @return 生成的图片 URL
     */
    private String callImageGenerateApi(String prompt, byte[] imageData, String size) throws Exception {
        String endpoint = qwenBaseUrl + "/images/generations";
        logger.info("调用图像API - 端点: {}, 模型: {}, 是否编辑: {}", endpoint, qwenImageModel, imageData != null);

        // DashScope 多模态对话格式：input.messages（仅支持 user 角色）
        java.util.List<java.util.Map<String, Object>> contentList = new java.util.ArrayList<>();
        contentList.add(java.util.Map.of("text", prompt));

        boolean isEdit = (imageData != null && imageData.length > 0);

        // qwen-image-edit-max 是编辑模型，必须包含图片内容
        if (!isEdit) {
            // 纯生成时创建指定尺寸的空白白色图片作为基底
            int[] dims = parseSize(size);
            imageData = createBlankImage(dims[0], dims[1]);
            logger.info("纯图像生成模式，已创建 {} 空白基底图片", size);
        }
        String base64Image = java.util.Base64.getEncoder().encodeToString(imageData);
        contentList.add(java.util.Map.of("image", "data:image/png;base64," + base64Image));

        java.util.Map<String, Object> message = java.util.Map.of(
                "role", "user",
                "content", contentList
        );

        String jsonBody = objectMapper.writeValueAsString(java.util.Map.of(
                "model", qwenImageModel,
                "input", java.util.Map.of("messages", java.util.List.of(message)),
                "parameters", java.util.Map.of("size", size, "n", 1)
        ));

        // 统一使用 JSON 请求体
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + qwenApiKey)
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();

        HttpResponse<String> response = imageHttpClient.send(request, HttpResponse.BodyHandlers.ofString());
        logger.info("图像API响应状态: {}, 响应体: {}", response.statusCode(), response.body());

        if (response.statusCode() != 200) {
            throw new RuntimeException("图像API调用失败: HTTP " + response.statusCode() + " - " + response.body());
        }

        // 解析响应 JSON，兼容多种格式
        JsonNode root = objectMapper.readTree(response.body());

        // 尝试 DashScope 多模态格式: output.choices[0].message.content[0].image
        JsonNode choicesNode = root.path("output").path("choices");
        if (choicesNode.isArray() && choicesNode.size() > 0) {
            JsonNode contentArr = choicesNode.get(0).path("message").path("content");
            if (contentArr.isArray()) {
                for (JsonNode item : contentArr) {
                    String image = item.path("image").asText("");
                    if (!image.isEmpty()) return image;
                }
            }
        }

        // 尝试 DashScope text2image 格式: output.results[0].url
        JsonNode resultsNode = root.path("output").path("results");
        if (resultsNode.isArray() && resultsNode.size() > 0) {
            String url = resultsNode.get(0).path("url").asText("");
            if (!url.isEmpty()) return url;
        }

        // 回退 OpenAI 格式: data[0].url
        JsonNode dataNode = root.path("data");
        if (dataNode.isArray() && dataNode.size() > 0) {
            String url = dataNode.get(0).path("url").asText("");
            if (!url.isEmpty()) return url;
        }

        throw new RuntimeException("图像API返回格式异常，无法提取图片URL: " + response.body());
    }

    /**
     * 创建空白白色 PNG 图片，用于纯图像生成时作为基底
     */
    private byte[] createBlankImage(int width, int height) throws Exception {
        java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g2d = image.createGraphics();
        g2d.setColor(java.awt.Color.WHITE);
        g2d.fillRect(0, 0, width, height);
        g2d.dispose();

        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(image, "png", baos);
        return baos.toByteArray();
    }

    /**
     * 解析尺寸字符串，如 "1024*1024" -> [1024, 1024]
     */
    private int[] parseSize(String size) {
        if (size == null || size.isBlank()) {
            return new int[]{1024, 1024};
        }
        try {
            String[] parts = size.split("\\*");
            return new int[]{Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim())};
        } catch (Exception e) {
            return new int[]{1024, 1024};
        }
    }

    /**
     * 增强图片编辑提示词，通过结构化指令防止文字重影/排版变化
     * 策略：将保留约束放在提示词最前面（模型对开头更敏感）
     * 注意：qwen-image-edit-max API 不支持 system 消息，所有约束必须嵌入 user 消息的 text 中
     */
    private String enhanceEditPrompt(String userPrompt) {
        // 如果用户提示词中已包含文字相关指令，则不重复追加
        if (userPrompt.contains("文字") || userPrompt.contains("字体") || userPrompt.contains("文本")
                || userPrompt.contains("text") || userPrompt.contains("Text")) {
            return userPrompt;
        }
        return """
                请对这张图片进行编辑，严格遵守以下规则：
                1. 仅修改我描述的部分，其余所有内容保持完全不变
                2. 图中已有的文字、数字、符号、排版布局必须精确保留，禁止产生重影、模糊、变形或位移
                3. 保持原图的整体构图和色彩风格
                
                我的修改要求：%s""".formatted(userPrompt);
    }

    /**
     * 检测图片尺寸，返回 "宽*高" 格式字符串
     * 自动将尺寸调整到 API 有效范围 [512, 2048]，同时保持宽高比
     */
    private String detectImageSize(byte[] imageData) {
        try {
            java.awt.image.BufferedImage image = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(imageData));
            if (image != null) {
                int w = image.getWidth();
                int h = image.getHeight();
                int[] clamped = clampToValidRange(w, h);
                logger.info("原图尺寸: {}x{}, 调整后: {}x{}", w, h, clamped[0], clamped[1]);
                return clamped[0] + "*" + clamped[1];
            }
        } catch (Exception e) {
            logger.warn("无法检测图片尺寸，使用默认 1024*1024", e);
        }
        return "1024*1024";
    }

    /**
     * 将宽高调整到 API 有效范围 [512, 2048]，保持宽高比
     */
    private int[] clampToValidRange(int width, int height) {
        final int MIN = 512;
        final int MAX = 2048;

        // 如果已经在有效范围内，直接返回
        if (width >= MIN && width <= MAX && height >= MIN && height <= MAX) {
            return new int[]{width, height};
        }

        double ratio = (double) width / height;

        if (width < MIN || height < MIN) {
            // 太小，按短边缩放到 MIN
            if (width <= height) {
                width = MIN;
                height = (int) Math.round(width / ratio);
            } else {
                height = MIN;
                width = (int) Math.round(height * ratio);
            }
        }

        if (width > MAX || height > MAX) {
            // 太大，按长边缩放到 MAX
            if (width >= height) {
                width = MAX;
                height = (int) Math.round(width / ratio);
            } else {
                height = MAX;
                width = (int) Math.round(height * ratio);
            }
        }

        return new int[]{width, height};
    }

    /**
     * 根据文件扩展名确定MIME类型
     */
    private String determineMimeType(String filename) {
        if (filename == null) {
            return "image/png";
        }
        String extension = filename.substring(filename.lastIndexOf('.') + 1).toLowerCase();
        return switch (extension) {
            case "jpg", "jpeg" -> "image/jpeg";
            case "png" -> "image/png";
            case "gif" -> "image/gif";
            case "webp" -> "image/webp";
            default -> "image/png";
        };
    }

    /**
     * 根据模型名称获取对应的 ChatClient
     */
    private ChatClient getChatClient(String model) {
        return switch (model.toLowerCase()) {
            case "openai" -> openAiChatClient;
            case "deepseek" -> deepSeekChatClient;
            case "qwen", "qwen-image" -> qwenChatClient;
            default -> throw new IllegalArgumentException("不支持的模型: " + model
                    + "，支持的模型: openai, deepseek, qwen");
        };
    }

    /**
     * 从会话历史构建 Spring AI 的 Message 列表（上下文）
     * 将 ChatMessage 转换为 Spring AI 的 Message 接口实现
     */
    private List<Message> buildContextMessages(String conversationId) {
        List<Message> messages = new ArrayList<>();
        List<ChatMessage> chatMessages = conversationRepository.findMessagesByConversationId(conversationId);
        for (ChatMessage msg : chatMessages) {
            switch (msg.getRole()) {
                case "user" -> messages.add(new UserMessage(msg.getContent()));
                case "assistant" -> messages.add(new AssistantMessage(msg.getContent()));
                // system 消息可根据需要扩展
            }
        }
        return messages;
    }
}
