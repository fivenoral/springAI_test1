package org.example.springai_test1.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.ratelimiter.RateLimiter;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import org.example.springai_test1.dto.ChatResult;
import org.example.springai_test1.model.ChatMessage;
import org.example.springai_test1.model.Conversation;
import org.example.springai_test1.service.ChatService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.ClassUtils;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 聊天服务实现
 * <p>
 * 核心逻辑：
 * 1. 根据模型名称选择对应的 ChatClient Bean
 * 2. 从仓储层加载历史消息构建上下文
 * 3. 调用 AI 模型获取回复
 * 4. 将用户消息和 AI 回复持久化到仓储层
 * <p>
 * 高并发优化：
 * <ul>
 *   <li>移除类级 @Transactional，所有 DB 操作委托给 {@link ChatTransactionalService}，
 *       避免 AI 调用期间（5~60s）持有数据库连接</li>
 *   <li>使用全局共享的 {@link HttpClient} Bean（带连接池），替代每次 newHttpClient()</li>
 *   <li>工具定义启动时构建一次并缓存，避免每次请求反射扫描</li>
 *   <li>上下文窗口裁剪：长对话下只取最近 N 条消息，避免 token 数爆炸</li>
 * </ul>
 */
@Service
public class ChatServiceImpl implements ChatService {

    private static final Logger logger = LoggerFactory.getLogger(ChatServiceImpl.class);

    @Value("${spring.ai.qwen.api-key:}")
    private String qwenApiKey;

    @Value("${spring.ai.qwen.base-url:https://aigw.telecomjs.com/v1}")
    private String qwenBaseUrl;

    @Value("${spring.ai.qwen.image.model:qwen-image-edit-max}")
    private String qwenImageModel;

    @Value("${spring.ai.deepseek.api-key:}")
    private String deepSeekApiKey;

    @Value("${spring.ai.deepseek.base-url:https://aigw.telecomjs.com/v1}")
    private String deepSeekBaseUrl;

    @Value("${spring.ai.deepseek.chat.options.model:deepseek-v4-pro}")
    private String deepSeekModel;

    /** 上下文窗口大小：仅取最近 N 条消息作为 LLM 上下文，避免 token 爆炸 */
    @Value("${app.chat.context-window:40}")
    private int contextWindow;

    /** 全局共享的 HttpClient（带连接池），由 HttpClientConfig 配置 */
    @Autowired
    private HttpClient httpClient;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Resource(name = "openAiChatClient")
    private ChatClient openAiChatClient;

    @Resource(name = "deepSeekChatClient")
    private ChatClient deepSeekChatClient;

    @Resource(name = "qwenChatClient")
    private ChatClient qwenChatClient;

    /**
     * 事务性 DB 操作服务：所有数据库读写都通过它，
     * 每个方法独立短事务，毫秒级完成，AI 调用期间不占用连接
     */
    @Autowired
    private ChatTransactionalService txService;

    @Autowired(required = false)
    private Object[] allToolCallbacks;

    @Autowired(required = false)
    private SyncMcpToolCallbackProvider mcpToolProvider;

    @Autowired
    private VectorStore vectorStore;

    /** AI 网关熔断器（Resilience4j）：连续失败自动熔断，避免拖垮整个应用 */
    @Autowired
    private CircuitBreaker aiCircuitBreaker;

    /** AI 网关限流器（Resilience4j）：限制每秒 AI 调用次数，保护下游网关 */
    @Autowired
    private RateLimiter aiRateLimiter;

    /** 缓存的工具定义（启动时构建一次，避免每次请求反射扫描） */
    private List<Map<String, Object>> cachedToolDefinitions;

    @PostConstruct
    public void init() {
        if (allToolCallbacks == null) {
            logger.warn("[启动诊断] allToolCallbacks = null，本地工具注入失败！");
        } else {
            logger.info("[启动诊断] allToolCallbacks 共 {} 个 bean:", allToolCallbacks.length);
            for (Object bean : allToolCallbacks) {
                Class<?> userClass = ClassUtils.getUserClass(bean.getClass());
                logger.info("  - bean: {} (代理类: {})", userClass.getSimpleName(), bean.getClass().getName());
                for (Method m : userClass.getDeclaredMethods()) {
                    if (m.isAnnotationPresent(Tool.class)) {
                        logger.info("    @Tool 方法: {} - {}", m.getName(), m.getAnnotation(Tool.class).description().substring(0, Math.min(60, m.getAnnotation(Tool.class).description().length())));
                    }
                }
            }
        }
        if (mcpToolProvider == null) {
            logger.warn("[启动诊断] mcpToolProvider = null，MCP 工具不可用！");
        } else {
            try {
                ToolCallback[] mcpCallbacks = mcpToolProvider.getToolCallbacks();
                logger.info("[启动诊断] MCP 工具共 {} 个:", mcpCallbacks != null ? mcpCallbacks.length : 0);
                if (mcpCallbacks != null) {
                    for (ToolCallback cb : mcpCallbacks) {
                        logger.info("  - MCP 工具: {} - {}", cb.getToolDefinition().name(), cb.getToolDefinition().description());
                    }
                }
            } catch (Exception e) {
                logger.warn("[启动诊断] 获取 MCP 工具失败: {}", e.getMessage());
            }
        }
        // 启动时一次性构建工具定义，避免每次请求反射扫描
        this.cachedToolDefinitions = buildToolDefinitions();
        logger.info("[启动诊断] 工具定义已缓存，共 {} 个", cachedToolDefinitions.size());
    }

    @Override
    public ChatResult chat(String model, String conversationId, String userMessage) {
        return chat(model, conversationId, userMessage, false);
    }

    @Override
    public ChatResult chat(String model, String conversationId, String userMessage, boolean thinkingMode) {
        logger.info("收到聊天请求 - 模型: {}, 会话ID: {}, 用户消息: {}", model, conversationId, userMessage);

        // 1. 获取或创建会话（短事务）
        String convId = resolveConversationId(conversationId, model);

        // 2. 记录用户消息（短事务，完成后立即释放 DB 连接）
        ChatMessage userMsg = new ChatMessage("user", userMessage, model, convId);
        txService.saveUserMessage(userMsg);

        // 3. 加载上下文（短事务只读，使用上下文窗口裁剪避免 token 爆炸）
        List<ChatMessage> recentMessages = txService.findRecentMessages(convId, contextWindow);

        // 4. 调用 AI 模型（无事务，不占 DB 连接，5~60s 期间连接池完全空闲）
        String aiReply;
        String reasoningContent = null;

        if ("deepseek".equalsIgnoreCase(model)) {
            boolean effectiveThinking = thinkingMode || hasReasoningContentInHistory(recentMessages);
            List<Map<String, Object>> rawMessages = buildRawContextMessages(recentMessages, effectiveThinking);
            try {
                String[] rawResult = callDeepSeekRaw(rawMessages, effectiveThinking);
                aiReply = rawResult[0];
                reasoningContent = rawResult[1];
            } catch (Exception e) {
                logger.error("DeepSeek 调用失败", e);
                throw new RuntimeException("DeepSeek 调用失败: " + e.getMessage(), e);
            }
            if (!thinkingMode) {
                reasoningContent = null;
            }
        } else {
            List<Message> contextMessages = buildContextMessages(recentMessages);
            ChatClient chatClient = getChatClient(model);
            // 用熔断器 + 限流器包装 AI 调用，保护下游网关
            ChatResponse chatResponse = aiRateLimiter.executeSupplier(
                    aiCircuitBreaker.decorateSupplier(() -> chatClient.prompt()
                            .messages(contextMessages)
                            .user(u -> u.text(userMessage))
                            .call()
                            .chatResponse()));
            aiReply = chatResponse.getResult().getOutput().getText();

            Map<String, Object> msgMeta = chatResponse.getResult().getOutput().getMetadata();
            if (msgMeta != null) {
                Object rc = msgMeta.get("reasoningContent");
                if (rc == null) rc = msgMeta.get("reasoning_content");
                if (rc != null) reasoningContent = rc.toString();
            }
        }
        logger.info("AI响应 - 模型: {}, 包含reasoning_content: {}", model, reasoningContent != null);

        // 5. 记录 AI 回复（短事务）
        ChatMessage assistantMsg = new ChatMessage("assistant", aiReply, model, convId);
        if (reasoningContent != null) {
            assistantMsg.setReasoningContent(reasoningContent);
        }
        txService.saveAssistantMessage(assistantMsg);

        logger.info("AI回复完成 - 会话ID: {}, 模型: {}, 回复长度: {}", convId, model, aiReply.length());
        return new ChatResult(aiReply, reasoningContent);
    }

    @Override
    public String generateImage(String prompt, String conversationId, String size) {
        logger.info("收到图片生成请求 - 提示词: {}, 会话ID: {}, 尺寸: {}", prompt, conversationId, size);

        try {
            String convId = resolveConversationId(conversationId, "qwen-image");

            ChatMessage userMsg = new ChatMessage("user", prompt, "qwen-image", convId);
            txService.saveUserMessage(userMsg);

            String imageUrl = callImageGenerateApi(prompt, null, size);
            logger.info("图像生成API返回URL: {}", imageUrl);

            String localImageUrl = downloadImageToLocal(imageUrl, convId);

            ChatMessage assistantMsg = new ChatMessage("assistant", "图片已生成: " + localImageUrl, "qwen-image", convId);
            assistantMsg.setImageUrl(localImageUrl);
            txService.saveAssistantMessage(assistantMsg);

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
            String convId = resolveConversationId(conversationId, "qwen-image");

            ChatMessage userMsg = new ChatMessage("user", prompt, "qwen-image", convId);
            userMsg.setImageUrl(imageUrl);
            txService.saveUserMessage(userMsg);

            String imageSize = detectImageSize(imageData);
            logger.info("原图尺寸: {}", imageSize);

            String enhancedPrompt = enhanceEditPrompt(prompt);
            String editedImageUrl = callImageGenerateApi(enhancedPrompt, imageData, imageSize);

            logger.info("图像编辑API返回URL: {}", editedImageUrl);
            String localImageUrl = downloadImageToLocal(editedImageUrl, convId);

            ChatMessage assistantMsg = new ChatMessage("assistant", "图片已编辑: " + localImageUrl, "qwen-image", convId);
            assistantMsg.setImageUrl(localImageUrl);
            txService.saveAssistantMessage(assistantMsg);

            logger.info("图片编辑完成 - 会话ID: {}, 本地URL: {}", convId, localImageUrl);
            return localImageUrl;
        } catch (Exception e) {
            logger.error("图片编辑失败", e);
            throw new RuntimeException("图片编辑失败: " + e.getMessage(), e);
        }
    }

    @Override
    public Conversation getConversation(String conversationId) {
        Conversation conversation = txService.findConversation(conversationId)
                .orElseThrow(() -> new IllegalArgumentException("会话不存在: " + conversationId));
        return conversation;
    }

    @Override
    public List<Conversation> listConversations(String model) {
        // 委托给 ConversationRepository 的查询方法（只读，事务由 JPA 管理）
        return conversationRepository_findByModel(model);
    }

    @Override
    public List<Conversation> listAllConversations() {
        return conversationRepository_findAll();
    }

    @Override
    public void clearConversation(String conversationId) {
        txService.findConversation(conversationId)
                .orElseThrow(() -> new IllegalArgumentException("会话不存在: " + conversationId));
        txService.clearConversation(conversationId);
    }

    @Override
    public void deleteConversation(String conversationId) {
        txService.deleteConversation(conversationId);
    }

    @Override
    public void clearModelConversations(String model) {
        txService.deleteByModel(model);
    }

    @Override
    public void clearAll() {
        txService.deleteAll();
    }

    // ==================== 私有辅助方法 ====================

    /**
     * 获取或创建会话（短事务）
     */
    private String resolveConversationId(String conversationId, String model) {
        if (conversationId == null || conversationId.isBlank()) {
            String convId = UUID.randomUUID().toString();
            txService.saveConversation(new Conversation(convId, model));
            logger.info("创建新会话 - 会话ID: {}, 模型: {}", convId, model);
            return convId;
        }
        return txService.findConversation(conversationId)
                .map(c -> c.getId())
                .orElseGet(() -> {
                    String newId = conversationId;
                    txService.saveConversation(new Conversation(newId, model));
                    logger.info("会话不存在，创建新会话 - 会话ID: {}, 模型: {}", newId, model);
                    return newId;
                });
    }

    // 以下两个方法保留对 ConversationRepository 的只读查询（非事务性，JPA 自动管理）
    @Autowired
    private org.example.springai_test1.repository.ConversationRepository conversationRepository;

    private List<Conversation> conversationRepository_findByModel(String model) {
        return conversationRepository.findByModel(model);
    }

    private List<Conversation> conversationRepository_findAll() {
        return conversationRepository.findAll();
    }

    /**
     * 调用电信网关图像生成/编辑 API
     * POST {qwenBaseUrl}/images/generations
     */
    private String callImageGenerateApi(String prompt, byte[] imageData, String size) throws Exception {
        String endpoint = qwenBaseUrl + "/images/generations";
        logger.info("调用图像API - 端点: {}, 模型: {}, 是否编辑: {}", endpoint, qwenImageModel, imageData != null);

        java.util.List<java.util.Map<String, Object>> contentList = new java.util.ArrayList<>();
        contentList.add(java.util.Map.of("text", prompt));

        boolean isEdit = (imageData != null && imageData.length > 0);

        if (!isEdit) {
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

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + qwenApiKey)
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        logger.info("图像API响应状态: {}, 响应体长度: {}", response.statusCode(), response.body().length());

        if (response.statusCode() != 200) {
            throw new RuntimeException("图像API调用失败: HTTP " + response.statusCode() + " - " + response.body());
        }

        JsonNode root = objectMapper.readTree(response.body());

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

        JsonNode resultsNode = root.path("output").path("results");
        if (resultsNode.isArray() && resultsNode.size() > 0) {
            String url = resultsNode.get(0).path("url").asText("");
            if (!url.isEmpty()) return url;
        }

        JsonNode dataNode = root.path("data");
        if (dataNode.isArray() && dataNode.size() > 0) {
            String url = dataNode.get(0).path("url").asText("");
            if (!url.isEmpty()) return url;
        }

        throw new RuntimeException("图像API返回格式异常，无法提取图片URL: " + response.body());
    }

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

    private String enhanceEditPrompt(String userPrompt) {
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

    private int[] clampToValidRange(int width, int height) {
        final int MIN = 512;
        final int MAX = 2048;

        if (width >= MIN && width <= MAX && height >= MIN && height <= MAX) {
            return new int[]{width, height};
        }

        double ratio = (double) width / height;

        if (width < MIN || height < MIN) {
            if (width <= height) {
                width = MIN;
                height = (int) Math.round(width / ratio);
            } else {
                height = MIN;
                width = (int) Math.round(height * ratio);
            }
        }

        if (width > MAX || height > MAX) {
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

    private ChatClient getChatClient(String model) {
        return switch (model.toLowerCase()) {
            case "openai" -> openAiChatClient;
            case "deepseek" -> deepSeekChatClient;
            case "qwen", "qwen-image" -> qwenChatClient;
            default -> throw new IllegalArgumentException("不支持的模型: " + model
                    + "，支持的模型: openai, deepseek, qwen");
        };
    }

    private String downloadImageToLocal(String imageUrl, String conversationId) throws Exception {
        String filename = extractFilenameFromUrl(imageUrl);
        String extension = filename.contains(".") ? filename.substring(filename.lastIndexOf('.')) : ".png";
        String uniqueFilename = conversationId + "_" + UUID.randomUUID().toString() + extension;

        java.nio.file.Path uploadPath = java.nio.file.Paths.get("uploads").toAbsolutePath();
        java.nio.file.Files.createDirectories(uploadPath);

        java.net.URL url = new java.net.URL(imageUrl);
        java.io.InputStream inputStream = url.openStream();
        java.nio.file.Path outputPath = uploadPath.resolve(uniqueFilename);
        java.nio.file.Files.copy(inputStream, outputPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        inputStream.close();

        logger.info("图片已下载到: {}", outputPath);
        return "/uploads/" + uniqueFilename;
    }

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

    // ==================== DeepSeek 原生 HTTP 调用（支持 reasoning_content 回传 + 工具调用） ====================

    /**
     * 检查上下文消息中是否存在带 reasoning_content 的 assistant 消息
     * （基于已加载的上下文，避免再次查询数据库）
     */
    private boolean hasReasoningContentInHistory(List<ChatMessage> messages) {
        for (ChatMessage msg : messages) {
            if ("assistant".equals(msg.getRole())
                    && msg.getReasoningContent() != null
                    && !msg.getReasoningContent().isBlank()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 构建原始消息列表（用于 DeepSeek 直连调用）
     */
    private List<Map<String, Object>> buildRawContextMessages(List<ChatMessage> messages, boolean includeReasoning) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (ChatMessage msg : messages) {
            Map<String, Object> message = new HashMap<>();
            message.put("role", msg.getRole());
            message.put("content", msg.getContent());
            if ("assistant".equals(msg.getRole()) && includeReasoning) {
                String rc = (msg.getReasoningContent() != null && !msg.getReasoningContent().isBlank())
                        ? msg.getReasoningContent() : "";
                message.put("reasoning_content", rc);
            }
            result.add(message);
        }
        return result;
    }

    /**
     * 从 @Tool 注解的 Bean 中提取工具定义，转换为 OpenAI function schema 格式
     * 启动时一次性构建并缓存，避免每次请求反射扫描
     */
    private List<Map<String, Object>> buildToolDefinitions() {
        List<Map<String, Object>> tools = new ArrayList<>();

        if (allToolCallbacks != null) {
            for (Object bean : allToolCallbacks) {
                Class<?> userClass = ClassUtils.getUserClass(bean.getClass());
                for (Method method : userClass.getDeclaredMethods()) {
                    Tool toolAnnotation = method.getAnnotation(Tool.class);
                    if (toolAnnotation == null) continue;

                    Map<String, Object> function = new HashMap<>();
                    function.put("name", method.getName());
                    function.put("description", toolAnnotation.description());

                    Map<String, Object> properties = new HashMap<>();
                    List<String> required = new ArrayList<>();
                    for (Parameter param : method.getParameters()) {
                        ToolParam toolParam = param.getAnnotation(ToolParam.class);
                        String paramName = param.getName();
                        Map<String, Object> prop = new HashMap<>();
                        prop.put("type", "string");
                        if (toolParam != null) {
                            prop.put("description", toolParam.description());
                        }
                        properties.put(paramName, prop);
                        required.add(paramName);
                    }

                    Map<String, Object> parameters = new HashMap<>();
                    parameters.put("type", "object");
                    parameters.put("properties", properties);
                    parameters.put("required", required);
                    function.put("parameters", parameters);

                    Map<String, Object> toolDef = new HashMap<>();
                    toolDef.put("type", "function");
                    toolDef.put("function", function);
                    tools.add(toolDef);
                }
            }
        }

        if (mcpToolProvider != null) {
            try {
                ToolCallback[] mcpCallbacks = mcpToolProvider.getToolCallbacks();
                if (mcpCallbacks != null) {
                    for (ToolCallback callback : mcpCallbacks) {
                        var toolDef = callback.getToolDefinition();
                        Map<String, Object> function = new HashMap<>();
                        function.put("name", toolDef.name());
                        function.put("description", toolDef.description());
                        try {
                            Map<String, Object> schema = objectMapper.readValue(toolDef.inputSchema(), Map.class);
                            function.put("parameters", schema);
                        } catch (Exception e) {
                            function.put("parameters", Map.of("type", "object", "properties", Map.of()));
                        }

                        Map<String, Object> toolEntry = new HashMap<>();
                        toolEntry.put("type", "function");
                        toolEntry.put("function", function);
                        tools.add(toolEntry);
                    }
                }
            } catch (Exception e) {
                logger.warn("获取 MCP 工具失败: {}", e.getMessage());
            }
        }

        return tools;
    }

    private String executeToolCall(String functionName, String argumentsJson) {
        if (allToolCallbacks != null) {
            for (Object bean : allToolCallbacks) {
                Class<?> userClass = ClassUtils.getUserClass(bean.getClass());
                for (Method method : userClass.getDeclaredMethods()) {
                    Tool toolAnnotation = method.getAnnotation(Tool.class);
                    if (toolAnnotation == null || !method.getName().equals(functionName)) continue;

                    try {
                        method.setAccessible(true);
                        Parameter[] params = method.getParameters();
                        Object[] methodArgs = new Object[params.length];
                        Map<String, Object> args = argumentsJson != null && !argumentsJson.isBlank()
                                ? objectMapper.readValue(argumentsJson, Map.class) : Map.of();
                        for (int i = 0; i < params.length; i++) {
                            Object value = args.get(params[i].getName());
                            if (value != null) {
                                methodArgs[i] = objectMapper.convertValue(value, params[i].getType());
                            }
                        }
                        Object result = method.invoke(bean, methodArgs);
                        String resultStr = result != null ? result.toString() : "OK";
                        logger.info("本地工具调用成功: {} -> {}", functionName, resultStr.length() > 100 ? resultStr.substring(0, 100) + "..." : resultStr);
                        return resultStr;
                    } catch (Exception e) {
                        logger.error("本地工具调用失败: {}", functionName, e);
                        return "工具调用失败: " + e.getMessage();
                    }
                }
            }
        }

        if (mcpToolProvider != null) {
            try {
                ToolCallback[] mcpCallbacks = mcpToolProvider.getToolCallbacks();
                if (mcpCallbacks != null) {
                    for (ToolCallback callback : mcpCallbacks) {
                        if (callback.getToolDefinition().name().equals(functionName)) {
                            String result = callback.call(argumentsJson != null ? argumentsJson : "{}");
                            logger.info("MCP 工具调用成功: {} -> {}", functionName, result.length() > 100 ? result.substring(0, 100) + "..." : result);
                            return result;
                        }
                    }
                }
            } catch (Exception e) {
                logger.error("MCP 工具调用失败: {}", functionName, e);
                return "MCP 工具调用失败: " + e.getMessage();
            }
        }

        return "错误：找不到工具 " + functionName;
    }

    /**
     * 直接调用 DeepSeek API（绕过 Spring AI），支持工具调用循环
     */
    private String[] callDeepSeekRaw(List<Map<String, Object>> messages, boolean thinkingMode) throws Exception {
        // 使用启动时缓存的工具定义，避免每次请求反射扫描
        List<Map<String, Object>> toolDefinitions = cachedToolDefinitions;
        int maxTurns = 10;

        String finalContent = "";
        String finalReasoningContent = null;

        for (int turn = 0; turn < maxTurns; turn++) {
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", deepSeekModel);
            requestBody.put("messages", messages);
            if (thinkingMode) {
                requestBody.put("thinking", Map.of("type", "enabled"));
            }
            if (!toolDefinitions.isEmpty()) {
                requestBody.put("tools", toolDefinitions);
            }

            String jsonBody = objectMapper.writeValueAsString(requestBody);
            logger.info("DeepSeek 原生请求 (turn {}) - 思考模式: {}, tools数量: {}", turn + 1, thinkingMode, toolDefinitions.size());

            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(deepSeekBaseUrl + "/chat/completions"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + deepSeekApiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .timeout(java.time.Duration.ofSeconds(120))
                    .build();

            // 使用全局共享的 HttpClient（带连接池）
            // 用熔断器 + 限流器包装 HTTP 调用，保护下游 AI 网关
            final HttpRequest finalHttpRequest = httpRequest;
            java.util.function.Supplier<HttpResponse<String>> httpCall = () -> {
                try {
                    return aiCircuitBreaker.decorateCheckedSupplier(
                            (io.github.resilience4j.core.functions.CheckedSupplier<HttpResponse<String>>) () ->
                                    httpClient.send(finalHttpRequest, HttpResponse.BodyHandlers.ofString())
                    ).get();
                } catch (Throwable t) {
                    throw new RuntimeException("DeepSeek HTTP 调用失败: " + t.getMessage(), t);
                }
            };
            HttpResponse<String> response = aiRateLimiter.executeSupplier(httpCall);

            if (response.statusCode() != 200) {
                logger.error("DeepSeek API 错误: {} - {}", response.statusCode(), response.body());
                throw new RuntimeException("DeepSeek API 返回 " + response.statusCode() + ": " + response.body());
            }

            JsonNode responseJson = objectMapper.readTree(response.body());
            JsonNode choice = responseJson.get("choices").get(0);
            JsonNode messageNode = choice.get("message");

            String content = messageNode.has("content") && !messageNode.get("content").isNull()
                    ? messageNode.get("content").asText() : "";
            String reasoningContent = messageNode.has("reasoning_content") && !messageNode.get("reasoning_content").isNull()
                    ? messageNode.get("reasoning_content").asText() : null;

            JsonNode toolCalls = messageNode.get("tool_calls");
            if (toolCalls == null || !toolCalls.isArray() || toolCalls.isEmpty()) {
                finalContent = content;
                finalReasoningContent = reasoningContent;
                logger.info("DeepSeek 最终响应 (turn {}) - content长度: {}, reasoning长度: {}",
                        turn + 1, content.length(), reasoningContent != null ? reasoningContent.length() : 0);
                break;
            }

            Map<String, Object> assistantMsg = new HashMap<>();
            assistantMsg.put("role", "assistant");
            assistantMsg.put("content", content.isEmpty() ? null : content);
            if (thinkingMode) {
                assistantMsg.put("reasoning_content", reasoningContent != null ? reasoningContent : "");
            }
            List<Map<String, Object>> toolCallsList = new ArrayList<>();
            for (JsonNode tc : toolCalls) {
                Map<String, Object> toolCall = new HashMap<>();
                toolCall.put("id", tc.get("id").asText());
                toolCall.put("type", tc.get("type").asText());
                Map<String, Object> function = new HashMap<>();
                function.put("name", tc.get("function").get("name").asText());
                function.put("arguments", tc.get("function").get("arguments").asText());
                toolCall.put("function", function);
                toolCallsList.add(toolCall);
            }
            assistantMsg.put("tool_calls", toolCallsList);
            messages.add(assistantMsg);

            for (JsonNode tc : toolCalls) {
                String toolCallId = tc.get("id").asText();
                String funcName = tc.get("function").get("name").asText();
                String funcArgs = tc.get("function").get("arguments").asText();

                logger.info("执行工具调用: {}({})", funcName, funcArgs.length() > 100 ? funcArgs.substring(0, 100) + "..." : funcArgs);
                String toolResult = executeToolCall(funcName, funcArgs);

                Map<String, Object> toolMsg = new HashMap<>();
                toolMsg.put("role", "tool");
                toolMsg.put("tool_call_id", toolCallId);
                toolMsg.put("content", toolResult);
                messages.add(toolMsg);
            }

            logger.info("工具调用轮次 {} 完成，继续下一轮", turn + 1);
        }

        return new String[]{finalContent, finalReasoningContent};
    }

    /**
     * 从会话历史构建 Spring AI 的 Message 列表（上下文）
     */
    private List<Message> buildContextMessages(List<ChatMessage> chatMessages) {
        List<Message> messages = new ArrayList<>();
        for (ChatMessage msg : chatMessages) {
            switch (msg.getRole()) {
                case "user" -> messages.add(new UserMessage(msg.getContent()));
                case "assistant" -> {
                    AssistantMessage assistantMessage = new AssistantMessage(msg.getContent());
                    if (msg.getReasoningContent() != null && !msg.getReasoningContent().isBlank()) {
                        assistantMessage.getMetadata().put("reasoningContent", msg.getReasoningContent());
                    }
                    messages.add(assistantMessage);
                }
            }
        }
        return messages;
    }
}
