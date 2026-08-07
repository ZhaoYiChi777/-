package com.intelligence.platform.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.intelligence.platform.entity.LlmConfig;
import com.intelligence.platform.mapper.LlmConfigMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * LLM调用服务
 * 参考 llm_wiki 的 llm-providers.ts / llm-client.ts
 * 支持国际提供商：OpenAI / Anthropic / Google / Ollama / Azure / Custom
 * 支持中国提供商：DeepSeek / Qwen / MiniMax / Moonshot / StepFun / Hunyuan / Doubao / SiliconFlow
 *
 * 模型用途（purpose）说明：
 *   chat      - 主对话 LLM（问答）
 *   extract   - 词条抽取专用 LLM（可独立配置，默认复用 chat）
 *   vlm       - 视觉语言模型（处理图片 / OCR）
 *   embedding - 文本向量化（转向量数据库）
 *   rerank    - 检索重排序
 *   ocr       - OCR（兼容旧配置，等价于 vlm）
 *   both      - 同时支持 chat + embedding
 */
@Service
public class LlmService {

    @Autowired
    private LlmConfigMapper llmConfigMapper;

    // ---- 主 Chat LLM（问答）----
    @Value("${llm.default.api-key:}")
    private String defaultApiKey;

    @Value("${llm.default.base-url:https://api.siliconflow.cn/v1}")
    private String defaultBaseUrl;

    @Value("${llm.default.model:Qwen/Qwen2.5-72B-Instruct}")
    private String defaultModel;

    @Value("${llm.default.provider:siliconflow}")
    private String defaultProvider;

    // ---- 词条抽取 LLM（extract）----
    @Value("${llm.extract.api-key:}")
    private String extractApiKey;

    @Value("${llm.extract.base-url:https://api.siliconflow.cn/v1}")
    private String extractBaseUrl;

    @Value("${llm.extract.model:Qwen/Qwen2.5-72B-Instruct}")
    private String extractModel;

    @Value("${llm.extract.provider:siliconflow}")
    private String extractProvider;

    // ---- VLM（图片处理）----
    @Value("${llm.vlm.api-key:}")
    private String vlmApiKey;

    @Value("${llm.vlm.base-url:https://api.siliconflow.cn/v1}")
    private String vlmBaseUrl;

    @Value("${llm.vlm.model:Qwen/Qwen2.5-VL-72B-Instruct}")
    private String vlmModel;

    @Value("${llm.vlm.provider:siliconflow}")
    private String vlmProvider;

    // ---- Embedding 模型 ----
    @Value("${embedding.default.api-key:}")
    private String embeddingApiKey;

    @Value("${embedding.default.base-url:https://api.siliconflow.cn/v1}")
    private String embeddingBaseUrl;

    @Value("${embedding.default.model:BAAI/bge-large-zh-v1.5}")
    private String embeddingModel;

    @Value("${embedding.default.provider:siliconflow}")
    private String embeddingProvider;

    // ---- Rerank 模型 ----
    @Value("${rerank.default.api-key:}")
    private String rerankApiKey;

    @Value("${rerank.default.base-url:https://api.siliconflow.cn/v1}")
    private String rerankBaseUrl;

    @Value("${rerank.default.model:BAAI/bge-reranker-v2-m3}")
    private String rerankModel;

    @Value("${rerank.default.provider:siliconflow}")
    private String rerankProvider;

    private final ObjectMapper mapper = new ObjectMapper();
    private static final Logger log = LoggerFactory.getLogger(LlmService.class);
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    // ============================================================
    // 配置获取方法
    // ============================================================

    /**
     * 获取当前启用的对话LLM配置（Chat）
     * 数据库配置优先；若无活跃数据库配置，则从环境变量构建默认配置
     */
    public LlmConfig getActiveChatConfig() {
        List<LlmConfig> configs = llmConfigMapper.selectList(
                new LambdaQueryWrapper<LlmConfig>()
                        .eq(LlmConfig::getEnabled, true)
                        .in(LlmConfig::getPurpose, "chat", "both")
                        .orderByDesc(LlmConfig::getId)
                        .last("LIMIT 1"));
        if (!configs.isEmpty()) {
            return configs.get(0);
        }
        // 数据库无活跃配置，回退到环境变量默认值
        return buildDefaultChatConfig();
    }

    /**
     * 获取词条抽取专用LLM配置（Extract）
     * 优先使用 purpose=extract 的数据库配置；
     * 其次使用环境变量 EXTRACT_LLM_*；
     * 最后回退到 chat 配置
     */
    public LlmConfig getActiveExtractConfig() {
        List<LlmConfig> configs = llmConfigMapper.selectList(
                new LambdaQueryWrapper<LlmConfig>()
                        .eq(LlmConfig::getEnabled, true)
                        .eq(LlmConfig::getPurpose, "extract")
                        .orderByDesc(LlmConfig::getId)
                        .last("LIMIT 1"));
        if (!configs.isEmpty()) {
            return configs.get(0);
        }
        // 尝试环境变量 EXTRACT_LLM_*
        if (extractApiKey != null && !extractApiKey.isEmpty()) {
            return buildExtractConfig();
        }
        // 回退到 chat 配置
        return getActiveChatConfig();
    }

    /**
     * 获取视觉语言模型配置（VLM / OCR）
     * 优先使用 purpose=vlm 的数据库配置；
     * 其次尝试 purpose=ocr（兼容旧版本）；
     * 再次使用环境变量 VLM_*；
     * 最后回退到 chat 配置
     */
    public LlmConfig getActiveVlmConfig() {
        // 优先查找 purpose=vlm
        List<LlmConfig> configs = llmConfigMapper.selectList(
                new LambdaQueryWrapper<LlmConfig>()
                        .eq(LlmConfig::getEnabled, true)
                        .eq(LlmConfig::getPurpose, "vlm")
                        .orderByDesc(LlmConfig::getId)
                        .last("LIMIT 1"));
        if (!configs.isEmpty()) {
            return configs.get(0);
        }
        // 兼容旧版 purpose=ocr
        configs = llmConfigMapper.selectList(
                new LambdaQueryWrapper<LlmConfig>()
                        .eq(LlmConfig::getEnabled, true)
                        .eq(LlmConfig::getPurpose, "ocr")
                        .orderByDesc(LlmConfig::getId)
                        .last("LIMIT 1"));
        if (!configs.isEmpty()) {
            return configs.get(0);
        }
        // 环境变量 VLM_*
        if (vlmApiKey != null && !vlmApiKey.isEmpty()) {
            return buildVlmConfig();
        }
        // 回退到 chat 配置
        return getActiveChatConfig();
    }

    /**
     * 获取 OCR 专用配置
     * 优先查找 purpose=ocr，回退到 purpose=vlm，再回退到 chat
     */
    public LlmConfig getActiveOcrConfig() {
        List<LlmConfig> configs = llmConfigMapper.selectList(
                new LambdaQueryWrapper<LlmConfig>()
                        .eq(LlmConfig::getEnabled, true)
                        .eq(LlmConfig::getPurpose, "ocr")
                        .orderByDesc(LlmConfig::getId)
                        .last("LIMIT 1"));
        if (!configs.isEmpty()) {
            return configs.get(0);
        }
        return getActiveVlmConfig();
    }

    /**
     * 获取当前启用的Embedding配置
     * 优先使用数据库配置；其次使用环境变量 EMBEDDING_*
     */
    public LlmConfig getActiveEmbeddingConfig() {
        List<LlmConfig> configs = llmConfigMapper.selectList(
                new LambdaQueryWrapper<LlmConfig>()
                        .eq(LlmConfig::getEnabled, true)
                        .in(LlmConfig::getPurpose, "embedding", "both")
                        .orderByDesc(LlmConfig::getId)
                        .last("LIMIT 1"));
        if (!configs.isEmpty()) {
            return configs.get(0);
        }
        // 环境变量回退
        if (embeddingApiKey != null && !embeddingApiKey.isEmpty()) {
            return buildEmbeddingConfig();
        }
        return null;
    }

    /**
     * 获取当前启用的Rerank配置
     * 优先使用数据库配置；其次使用环境变量 RERANK_*
     */
    public LlmConfig getActiveRerankConfig() {
        List<LlmConfig> configs = llmConfigMapper.selectList(
                new LambdaQueryWrapper<LlmConfig>()
                        .eq(LlmConfig::getEnabled, true)
                        .eq(LlmConfig::getPurpose, "rerank")
                        .orderByDesc(LlmConfig::getId)
                        .last("LIMIT 1"));
        if (!configs.isEmpty()) {
            return configs.get(0);
        }
        // 环境变量回退
        if (rerankApiKey != null && !rerankApiKey.isEmpty()) {
            return buildRerankConfig();
        }
        return null;
    }

    // ============================================================
    // 环境变量构建配置
    // ============================================================

    private LlmConfig buildDefaultChatConfig() {
        if (defaultApiKey == null || defaultApiKey.isEmpty()) {
            return null;
        }
        LlmConfig config = new LlmConfig();
        config.setName("默认Chat配置（环境变量）");
        config.setProvider(defaultProvider);
        config.setApiKey(defaultApiKey);
        config.setModel(defaultModel);
        config.setBaseUrl(defaultBaseUrl);
        config.setEnabled(true);
        config.setPurpose("chat");
        config.setMaxContextSize(4096);
        config.setApiMode("chat_completions");
        return config;
    }

    private LlmConfig buildExtractConfig() {
        LlmConfig config = new LlmConfig();
        config.setName("词条抽取LLM（环境变量）");
        config.setProvider(extractProvider);
        config.setApiKey(extractApiKey);
        config.setModel(extractModel);
        config.setBaseUrl(extractBaseUrl);
        config.setEnabled(true);
        config.setPurpose("extract");
        config.setMaxContextSize(4096);
        config.setApiMode("chat_completions");
        return config;
    }

    private LlmConfig buildVlmConfig() {
        LlmConfig config = new LlmConfig();
        config.setName("VLM视觉模型（环境变量）");
        config.setProvider(vlmProvider);
        config.setApiKey(vlmApiKey);
        config.setModel(vlmModel);
        config.setBaseUrl(vlmBaseUrl);
        config.setEnabled(true);
        config.setPurpose("vlm");
        config.setMaxContextSize(4096);
        config.setApiMode("chat_completions");
        return config;
    }

    private LlmConfig buildEmbeddingConfig() {
        LlmConfig config = new LlmConfig();
        config.setName("Embedding模型（环境变量）");
        config.setProvider(embeddingProvider);
        config.setApiKey(embeddingApiKey);
        config.setModel(embeddingModel);
        config.setBaseUrl(embeddingBaseUrl);
        config.setEnabled(true);
        config.setPurpose("embedding");
        config.setMaxContextSize(0);
        config.setApiMode("embeddings");
        return config;
    }

    private LlmConfig buildRerankConfig() {
        LlmConfig config = new LlmConfig();
        config.setName("Rerank模型（环境变量）");
        config.setProvider(rerankProvider);
        config.setApiKey(rerankApiKey);
        config.setModel(rerankModel);
        config.setBaseUrl(rerankBaseUrl);
        config.setEnabled(true);
        config.setPurpose("rerank");
        config.setMaxContextSize(0);
        config.setApiMode("rerank");
        return config;
    }

    // ============================================================
    // Chat API 调用
    // ============================================================

    /**
     * 发送对话请求
     * @param config LLM配置
     * @param systemPrompt 系统提示词
     * @param userMessage 用户消息
     * @return LLM回复文本
     */
    public String chat(LlmConfig config, String systemPrompt, String userMessage) throws Exception {
        return executeWithRetry(() -> {
            String url = resolveChatUrl(config);
            String body = buildChatBody(config, systemPrompt, userMessage);
            String authHeader = buildAuthHeader(config);

            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .version(HttpClient.Version.HTTP_1_1)
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(300))
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));

            if (authHeader != null) {
                reqBuilder.header("Authorization", authHeader);
            }

            // Anthropic / custom+anthropic_messages 特殊头
            boolean isAnthropicWire = "anthropic".equals(config.getProvider())
                    || ("custom".equals(config.getProvider()) && "anthropic_messages".equals(config.getApiMode()));
            if (isAnthropicWire && config.getApiKey() != null && !config.getApiKey().isEmpty()) {
                reqBuilder.header("x-api-key", config.getApiKey());
                reqBuilder.header("anthropic-version", "2023-06-01");
            }

            HttpResponse<String> response = httpClient.send(reqBuilder.build(),
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() / 100 != 2) {
                String errorBody = response.body();
                // 尝试解析 JSON 错误信息
                try {
                    JsonNode err = mapper.readTree(errorBody);
                    if (err.has("error")) {
                        JsonNode errorNode = err.get("error");
                        String msg = errorNode.has("message") ? errorNode.get("message").asText() : errorNode.asText();
                        throw new RuntimeException("API 错误 (" + response.statusCode() + "): " + msg);
                    }
                } catch (RuntimeException re) {
                    throw re;
                } catch (Exception ignored) {
                    // 非 JSON 响应
                }
                throw new RuntimeException("API 请求失败 (HTTP " + response.statusCode() + "): "
                        + (errorBody != null && errorBody.length() > 200 ? errorBody.substring(0, 200) : errorBody));
            }

            return parseChatResponse(config, response.body());
        }, "LLM调用");
    }

    /**
     * 使用当前活跃配置发送对话（Chat LLM）
     */
    public String chatWithActive(String systemPrompt, String userMessage) throws Exception {
        LlmConfig config = getActiveChatConfig();
        if (config == null) {
            return "错误：未配置LLM。请在后台系统配置中添加并启用LLM，或在 .env 文件中配置 LLM_API_KEY。";
        }
        return chat(config, systemPrompt, userMessage);
    }

    /**
     * 流式调用 LLM（Stream Chat）
     * 逐块将文本通过 chunkConsumer 回调传出
     *
     * @param config        LLM配置
     * @param systemPrompt  系统提示词
     * @param userMessage   用户消息
     * @param chunkConsumer 每收到一个文本块时的回调
     */
    public void streamChat(LlmConfig config, String systemPrompt, String userMessage,
                           Consumer<String> chunkConsumer) throws Exception {
        boolean isAnthropicWire = "anthropic".equals(config.getProvider())
                || ("custom".equals(config.getProvider()) && "anthropic_messages".equals(config.getApiMode()));

        String body = buildStreamChatBody(config, systemPrompt, userMessage, isAnthropicWire);
        String url = resolveChatUrl(config);
        String authHeader = buildAuthHeader(config);

        HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .version(HttpClient.Version.HTTP_1_1)
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(300))
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));

        if (authHeader != null) {
            reqBuilder.header("Authorization", authHeader);
        }

        if (isAnthropicWire && config.getApiKey() != null && !config.getApiKey().isEmpty()) {
            reqBuilder.header("x-api-key", config.getApiKey());
            reqBuilder.header("anthropic-version", "2023-06-01");
        }

        HttpResponse<java.io.InputStream> response = httpClient.send(reqBuilder.build(),
                HttpResponse.BodyHandlers.ofInputStream());

        if (response.statusCode() >= 400) {
            String errorBody = new String(response.body().readAllBytes());
            throw new RuntimeException("API 请求失败 (HTTP " + response.statusCode() + "): "
                    + errorBody.substring(0, Math.min(200, errorBody.length())));
        }

        // 逐行读取 SSE 流
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("data: ")) {
                    String data = line.substring(6).trim();
                    if ("[DONE]".equals(data)) break;

                    try {
                        JsonNode chunk = mapper.readTree(data);
                        String delta = extractStreamDelta(chunk, config);
                        if (delta != null && !delta.isEmpty()) {
                            chunkConsumer.accept(delta);
                        }
                    } catch (Exception e) {
                        // 跳过无法解析的行
                    }
                }
            }
        } catch (Exception e) {
            log.error("流式LLM调用异常: {}", e.getMessage(), e);
            throw e;
        }
    }

    /**
     * 使用当前活跃配置进行流式调用
     */
    public void streamChatWithActive(String systemPrompt, String userMessage,
                                     Consumer<String> chunkConsumer) throws Exception {
        LlmConfig config = getActiveChatConfig();
        if (config == null) {
            chunkConsumer.accept("错误：未配置LLM。请在后台系统配置中添加并启用LLM。");
            return;
        }
        streamChat(config, systemPrompt, userMessage, chunkConsumer);
    }

    private String buildStreamChatBody(LlmConfig config, String systemPrompt, String userMessage,
                                         boolean isAnthropicWire) throws Exception {
        int maxTokens = (config.getMaxContextSize() != null && config.getMaxContextSize() > 0)
                ? config.getMaxContextSize() : 4096;

        ObjectNode root = mapper.createObjectNode();
        root.put("model", config.getModel() != null ? config.getModel() : "gpt-3.5-turbo");
        root.put("stream", true);
        root.put("max_tokens", maxTokens);
        if (isQwenThinkingModel(config)) {
            root.put("thinking_budget", 8192);
        }

        ArrayNode messages = root.putArray("messages");
        if (isAnthropicWire && systemPrompt != null && !systemPrompt.isEmpty()) {
            root.put("system", systemPrompt);
        }
        if (systemPrompt != null && !systemPrompt.isEmpty() && !isAnthropicWire) {
            messages.addObject().put("role", "system").put("content", systemPrompt);
        }
        messages.addObject().put("role", "user").put("content", userMessage);
        return mapper.writeValueAsString(root);
    }

    private String extractStreamDelta(JsonNode chunk, LlmConfig config) {
        boolean isAnthropicWire = "anthropic".equals(config.getProvider())
                || ("custom".equals(config.getProvider()) && "anthropic_messages".equals(config.getApiMode()));

        if (isAnthropicWire) {
            // Anthropic: {"type":"content_block_delta","delta":{"type":"text_delta","text":"..."}}
            if (chunk.has("delta") && chunk.get("delta").has("text")) {
                return chunk.get("delta").get("text").asText();
            }
            return null;
        }

        // OpenAI 兼容格式: {"choices":[{"delta":{"content":"..."}}]}
        if (chunk.has("choices") && chunk.get("choices").isArray() && !chunk.get("choices").isEmpty()) {
            JsonNode choice = chunk.get("choices").get(0);
            if (choice.has("delta") && choice.get("delta").has("content")) {
                return choice.get("delta").get("content").asText();
            }
        }
        return null;
    }

    /**
     * 使用词条抽取专用 LLM 发送对话（Extract LLM）
     */
    public String chatWithExtract(String systemPrompt, String userMessage) throws Exception {
        LlmConfig config = getActiveExtractConfig();
        if (config == null) {
            return "错误：未配置词条抽取LLM。请在后台系统配置中添加 purpose=extract 的LLM配置。";
        }
        return chat(config, systemPrompt, userMessage);
    }

    // ============================================================
    // Vision API 调用（VLM）
    // ============================================================

    /**
     * 发送带图片的视觉对话请求（Vision API）
     * @param config LLM配置
     * @param systemPrompt 系统提示词
     * @param userMessage 用户文本消息
     * @param imageBase64 图片的Base64编码
     * @param imageMimeType 图片MIME类型（如 image/png）
     * @return LLM回复文本
     */
    public String chatWithVision(LlmConfig config, String systemPrompt, String userMessage,
                                  String imageBase64, String imageMimeType) throws Exception {
        String url = resolveChatUrl(config);
        String body = buildVisionBody(config, systemPrompt, userMessage, imageBase64, imageMimeType);
        String authHeader = buildAuthHeader(config);

        HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .version(HttpClient.Version.HTTP_1_1)
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(300))
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));

        if (authHeader != null) {
            reqBuilder.header("Authorization", authHeader);
        }

        boolean isAnthropicWire = "anthropic".equals(config.getProvider())
                || ("custom".equals(config.getProvider()) && "anthropic_messages".equals(config.getApiMode()));
        if (isAnthropicWire && config.getApiKey() != null && !config.getApiKey().isEmpty()) {
            reqBuilder.header("x-api-key", config.getApiKey());
            reqBuilder.header("anthropic-version", "2023-06-01");
        }

        HttpResponse<String> response = httpClient.send(reqBuilder.build(),
                HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() / 100 != 2) {
            String errorBody = response.body();
            try {
                JsonNode err = mapper.readTree(errorBody);
                if (err.has("error")) {
                    JsonNode errorNode = err.get("error");
                    String msg = errorNode.has("message") ? errorNode.get("message").asText() : errorNode.asText();
                    throw new RuntimeException("API 错误 (" + response.statusCode() + "): " + msg);
                }
            } catch (RuntimeException re) {
                throw re;
            } catch (Exception ignored) {
            }
            throw new RuntimeException("API 请求失败 (HTTP " + response.statusCode() + "): "
                    + (errorBody != null && errorBody.length() > 200 ? errorBody.substring(0, 200) : errorBody));
        }

        return parseChatResponse(config, response.body());
    }

    /**
     * 使用 VLM 配置发送图片对话
     */
    public String chatWithVlm(String systemPrompt, String userMessage,
                               String imageBase64, String imageMimeType) throws Exception {
        LlmConfig config = getActiveVlmConfig();
        if (config == null) {
            throw new RuntimeException("未配置 VLM。请在 .env 中设置 VLM_API_KEY 或在系统配置中添加 purpose=vlm 的配置。");
        }
        return chatWithVision(config, systemPrompt, userMessage, imageBase64, imageMimeType);
    }

    // ============================================================
    // Rerank API 调用
    // ============================================================

    /**
     * 使用 Rerank 模型对文档列表进行重排序
     * @param query 查询文本
     * @param documents 待重排序的文档文本列表
     * @return 重排序分数列表（与 documents 顺序对应）
     */
    public List<Double> rerank(String query, List<String> documents) throws Exception {
        LlmConfig config = getActiveRerankConfig();
        if (config == null) {
            // Rerank 不可用时返回均等分数（不影响功能）
            return documents.stream().map(d -> 1.0).toList();
        }
        return rerankWithConfig(config, query, documents);
    }

    public List<Double> rerankWithConfig(LlmConfig config, String query, List<String> documents) throws Exception {
        String baseUrl = config.getBaseUrl() != null ? config.getBaseUrl() : "https://api.siliconflow.cn/v1";
        String url = baseUrl.endsWith("/rerank") ? baseUrl : baseUrl + "/rerank";

        ObjectNode root = mapper.createObjectNode();
        root.put("model", config.getModel());
        root.put("query", query);
        ArrayNode docsArray = root.putArray("documents");
        for (String doc : documents) {
            docsArray.add(doc);
        }
        root.put("return_documents", false);
        root.put("top_n", documents.size());

        String body = mapper.writeValueAsString(root);

        HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .version(HttpClient.Version.HTTP_1_1)
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(60))
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));

        if (config.getApiKey() != null && !config.getApiKey().isEmpty()) {
            reqBuilder.header("Authorization", "Bearer " + config.getApiKey());
        }

        HttpResponse<String> response = httpClient.send(reqBuilder.build(),
                HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() / 100 != 2) {
            // Rerank 失败时返回均等分数（降级处理）
            return documents.stream().map(d -> 1.0).toList();
        }

        // 解析 rerank 响应
        JsonNode responseNode = mapper.readTree(response.body());
        Double[] scores = new Double[documents.size()];
        // 初始化为 0
        for (int i = 0; i < scores.length; i++) scores[i] = 0.0;

        JsonNode results = responseNode.get("results");
        if (results != null && results.isArray()) {
            for (JsonNode result : results) {
                int index = result.path("index").asInt();
                double score = result.path("relevance_score").asDouble(0.0);
                if (index >= 0 && index < scores.length) {
                    scores[index] = score;
                }
            }
        }
        return List.of(scores);
    }

    // ============================================================
    // URL 解析
    // ============================================================

    private String resolveChatUrl(LlmConfig config) {
        // custom provider: 根据apiMode决定URL后缀
        if ("custom".equals(config.getProvider())) {
            String base = config.getBaseUrl() != null ? config.getBaseUrl() : "http://localhost:11434";
            if ("anthropic_messages".equals(config.getApiMode())) {
                // Anthropic-compatible endpoint
                return base.endsWith("/messages") ? base : base + "/messages";
            }
            // OpenAI-compatible endpoint
            return base.endsWith("/chat/completions") ? base : base + "/chat/completions";
        }

        // 中国LLM提供商（均使用OpenAI兼容API格式）
        return switch (config.getProvider()) {
            // 国际提供商
            case "openai" -> "https://api.openai.com/v1/chat/completions";
            case "anthropic" -> "https://api.anthropic.com/v1/messages";
            case "google" -> "https://generativelanguage.googleapis.com/v1beta/models/"
                    + config.getModel() + ":generateContent?key=" + config.getApiKey();
            case "ollama" -> {
                String base = config.getBaseUrl() != null ? config.getBaseUrl() : "http://localhost:11434";
                yield base + "/v1/chat/completions";
            }
            case "azure" -> config.getBaseUrl();
            // 中国提供商 - DeepSeek（深度求索）
            case "deepseek" -> "https://api.deepseek.com/chat/completions";
            // 中国提供商 - Qwen（通义千问 - 阿里云）
            case "qwen" -> {
                String base = config.getBaseUrl() != null ? config.getBaseUrl()
                        : "https://dashscope.aliyuncs.com/compatible-mode/v1";
                yield base + "/chat/completions";
            }
            // 中国提供商 - MiniMax
            case "minimax" -> "https://api.minimax.chat/v1/text/chatcompletion_v2";
            // 中国提供商 - Moonshot（月之暗面）
            case "moonshot" -> "https://api.moonshot.cn/v1/chat/completions";
            // 中国提供商 - StepFun（阶跃星辰）
            case "stepfun" -> "https://api.stepfun.com/v1/chat/completions";
            // 中国提供商 - Hunyuan（混元 - 腾讯）
            case "hunyuan" -> "https://api.hunyuan.cloud.tencent.com/v1/chat/completions";
            // 中国提供商 - Doubao（豆包 - 字节跳动/火山引擎）
            case "doubao" -> {
                String base = config.getBaseUrl() != null ? config.getBaseUrl()
                        : "https://ark.cn-beijing.volces.com/api/v3";
                yield base + "/chat/completions";
            }
            // 中国提供商 - SiliconFlow（硅基流动）
            case "siliconflow" -> {
                String base = config.getBaseUrl() != null ? config.getBaseUrl()
                        : "https://api.siliconflow.cn/v1";
                yield base + "/chat/completions";
            }
            default -> {
                if (config.getBaseUrl() != null && !config.getBaseUrl().isEmpty()) {
                    String base = config.getBaseUrl();
                    yield base.endsWith("/chat/completions") ? base : base + "/chat/completions";
                }
                throw new RuntimeException("未配置 Base URL，请在设置中填写 API 地址");
            }
        };
    }

    private String buildChatBody(LlmConfig config, String systemPrompt, String userMessage) throws Exception {
        // Anthropic原生 或 custom+anthropic_messages模式
        if ("anthropic".equals(config.getProvider())
                || ("custom".equals(config.getProvider()) && "anthropic_messages".equals(config.getApiMode()))) {
            ObjectNode root = mapper.createObjectNode();
            root.put("model", config.getModel());
            root.put("max_tokens", 4096);
            if (systemPrompt != null) root.put("system", systemPrompt);
            ArrayNode messages = root.putArray("messages");
            ObjectNode msg = messages.addObject();
            msg.put("role", "user");
            msg.put("content", userMessage);
            return mapper.writeValueAsString(root);
        }

        if ("google".equals(config.getProvider())) {
            ObjectNode root = mapper.createObjectNode();
            ObjectNode contents = root.putArray("contents").addObject();
            contents.put("role", "user");
            contents.putArray("parts").addObject().put("text",
                    (systemPrompt != null ? systemPrompt + "\n\n" : "") + userMessage);
            return mapper.writeValueAsString(root);
        }

        // OpenAI / Ollama / Azure / Custom / 中国提供商（均使用OpenAI兼容格式）
        ObjectNode root = mapper.createObjectNode();
        root.put("model", config.getModel());
        ArrayNode messages = root.putArray("messages");
        if (systemPrompt != null) {
            ObjectNode sys = messages.addObject();
            sys.put("role", "system");
            sys.put("content", systemPrompt);
        }
        ObjectNode user = messages.addObject();
        user.put("role", "user");
        user.put("content", userMessage);
        root.put("max_tokens", 4096);
        if (isQwenThinkingModel(config)) {
            root.put("stream", false);
            root.put("thinking_budget", 8192);
        }
        return mapper.writeValueAsString(root);
    }

    private boolean isQwenThinkingModel(LlmConfig config) {
        return config != null
                && "custom".equalsIgnoreCase(config.getProvider())
                && config.getModel() != null
                && "Qwen3.6-27B".equalsIgnoreCase(config.getModel().trim());
    }

    private String buildAuthHeader(LlmConfig config) {
        if (config.getApiKey() == null || config.getApiKey().isEmpty()) return null;
        // Anthropic原生 或 custom+anthropic_messages → 使用 x-api-key
        if ("anthropic".equals(config.getProvider())
                || ("custom".equals(config.getProvider()) && "anthropic_messages".equals(config.getApiMode()))) {
            return null; // 使用 x-api-key header
        }
        return switch (config.getProvider()) {
            case "google" -> null; // 使用 URL 参数
            default -> "Bearer " + config.getApiKey();
        };
    }

    private String parseChatResponse(LlmConfig config, String responseBody) throws Exception {
        JsonNode root = mapper.readTree(responseBody);

        // Anthropic原生 或 custom+anthropic_messages
        if ("anthropic".equals(config.getProvider())
                || ("custom".equals(config.getProvider()) && "anthropic_messages".equals(config.getApiMode()))) {
            JsonNode content = root.get("content");
            if (content != null && content.isArray() && !content.isEmpty()) {
                return content.get(0).get("text").asText();
            }
            return root.has("error") ? "错误: " + root.get("error").get("message").asText() : responseBody;
        }

        if ("google".equals(config.getProvider())) {
            JsonNode candidates = root.get("candidates");
            if (candidates != null && candidates.isArray() && !candidates.isEmpty()) {
                JsonNode parts = candidates.get(0).get("content").get("parts");
                if (parts != null && parts.isArray() && !parts.isEmpty()) {
                    return parts.get(0).get("text").asText();
                }
            }
            return responseBody;
        }

        // OpenAI / Ollama / Azure / Custom / 中国提供商
        JsonNode choices = root.get("choices");
        if (choices != null && choices.isArray() && !choices.isEmpty()) {
            JsonNode message = choices.get(0).get("message");
            if (message != null && message.has("content")) {
                return message.get("content").asText();
            }
        }
        if (choices == null || !choices.isArray() || choices.isEmpty()) {
            log.warn("LLM响应中choices数组为空");
        }
        if (root.has("error")) {
            return "错误: " + root.get("error").get("message").asText();
        }
        return responseBody;
    }

    private String buildVisionBody(LlmConfig config, String systemPrompt, String userMessage,
                                    String imageBase64, String imageMimeType) throws Exception {
        // Anthropic 原生格式
        if ("anthropic".equals(config.getProvider())
                || ("custom".equals(config.getProvider()) && "anthropic_messages".equals(config.getApiMode()))) {
            ObjectNode root = mapper.createObjectNode();
            root.put("model", config.getModel());
            root.put("max_tokens", 4096);
            if (systemPrompt != null) root.put("system", systemPrompt);
            ArrayNode messages = root.putArray("messages");
            ObjectNode msg = messages.addObject();
            msg.put("role", "user");
            ArrayNode contentArr = msg.putArray("content");
            ObjectNode imageNode = contentArr.addObject();
            imageNode.put("type", "image");
            ObjectNode source = imageNode.putObject("source");
            source.put("type", "base64");
            source.put("media_type", imageMimeType);
            source.put("data", imageBase64);
            contentArr.addObject().put("type", "text").put("text", userMessage);
            return mapper.writeValueAsString(root);
        }

        // Google Gemini 格式
        if ("google".equals(config.getProvider())) {
            ObjectNode root = mapper.createObjectNode();
            ObjectNode contents = root.putArray("contents").addObject();
            contents.put("role", "user");
            ArrayNode parts = contents.putArray("parts");
            ObjectNode inlineData = parts.addObject().putObject("inline_data");
            inlineData.put("mime_type", imageMimeType);
            inlineData.put("data", imageBase64);
            parts.addObject().put("text", (systemPrompt != null ? systemPrompt + "\n\n" : "") + userMessage);
            return mapper.writeValueAsString(root);
        }

        // OpenAI兼容格式（含中国提供商，SiliconFlow Qwen2.5-VL 支持）
        ObjectNode root = mapper.createObjectNode();
        root.put("model", config.getModel());
        ArrayNode messages = root.putArray("messages");
        if (systemPrompt != null) {
            messages.addObject().put("role", "system").put("content", systemPrompt);
        }
        ObjectNode user = messages.addObject();
        user.put("role", "user");
        ArrayNode userContent = user.putArray("content");
        ObjectNode textPart = userContent.addObject();
        textPart.put("type", "text");
        textPart.put("text", userMessage);
        ObjectNode imagePart = userContent.addObject();
        imagePart.put("type", "image_url");
        imagePart.putObject("image_url")
                .put("url", "data:" + imageMimeType + ";base64," + imageBase64);
        root.put("max_tokens", 4096);
        return mapper.writeValueAsString(root);
    }

    // ============================================================
    // 支持的提供商列表（供前端使用）
    // ============================================================

    /**
     * 获取所有支持的LLM提供商列表（供前端使用）
     */
    public java.util.List<java.util.Map<String, String>> getSupportedProviders() {
        return java.util.List.of(
                // 中国提供商
                java.util.Map.of("value", "siliconflow", "label", "SiliconFlow (硅基流动)",
                        "desc", "国内聚合大模型平台，支持多种开源模型", "region", "cn",
                        "baseUrl", "https://api.siliconflow.cn/v1"),
                java.util.Map.of("value", "deepseek", "label", "DeepSeek (深度求索)",
                        "desc", "国产大模型，高性价比", "region", "cn",
                        "baseUrl", "https://api.deepseek.com"),
                java.util.Map.of("value", "qwen", "label", "Qwen (通义千问)",
                        "desc", "阿里云大模型", "region", "cn",
                        "baseUrl", "https://dashscope.aliyuncs.com/compatible-mode/v1"),
                java.util.Map.of("value", "minimax", "label", "MiniMax",
                        "desc", "国内大模型平台", "region", "cn",
                        "baseUrl", "https://api.minimax.chat/v1"),
                java.util.Map.of("value", "moonshot", "label", "Moonshot (月之暗面)",
                        "desc", "Kimi大模型", "region", "cn",
                        "baseUrl", "https://api.moonshot.cn/v1"),
                java.util.Map.of("value", "stepfun", "label", "StepFun (阶跃星辰)",
                        "desc", "国产大模型", "region", "cn",
                        "baseUrl", "https://api.stepfun.com/v1"),
                java.util.Map.of("value", "hunyuan", "label", "Hunyuan (混元)",
                        "desc", "腾讯大模型", "region", "cn",
                        "baseUrl", "https://api.hunyuan.cloud.tencent.com/v1"),
                java.util.Map.of("value", "doubao", "label", "Doubao (豆包)",
                        "desc", "字节跳动/火山引擎大模型", "region", "cn",
                        "baseUrl", "https://ark.cn-beijing.volces.com/api/v3"),
                // 国际提供商
                java.util.Map.of("value", "openai", "label", "OpenAI",
                        "desc", "GPT系列模型", "region", "global",
                        "baseUrl", "https://api.openai.com/v1"),
                java.util.Map.of("value", "anthropic", "label", "Anthropic",
                        "desc", "Claude系列模型", "region", "global",
                        "baseUrl", "https://api.anthropic.com/v1"),
                java.util.Map.of("value", "google", "label", "Google",
                        "desc", "Gemini系列模型", "region", "global",
                        "baseUrl", ""),
                java.util.Map.of("value", "ollama", "label", "Ollama (本地)",
                        "desc", "本地运行开源模型", "region", "local",
                        "baseUrl", "http://localhost:11434"),
                java.util.Map.of("value", "azure", "label", "Azure OpenAI",
                        "desc", "微软Azure托管OpenAI", "region", "global",
                        "baseUrl", ""),
                java.util.Map.of("value", "custom", "label", "Custom (自定义)",
                        "desc", "自定义API端点", "region", "custom",
                        "baseUrl", "")
        );
    }

    /**
     * 带指数退避重试的LLM调用
     */
    private String executeWithRetry(Callable<String> task, String operationName) throws Exception {
        int maxRetries = 3;
        long baseDelay = 1000;
        Exception lastException = null;

        for (int attempt = 0; attempt < maxRetries; attempt++) {
            try {
                return task.call();
            } catch (Exception e) {
                lastException = e;
                if (attempt < maxRetries - 1) {
                    long delay = baseDelay * (1L << attempt); // 1s, 2s, 4s
                    log.warn("{} 第{}次失败，{}ms后重试: {}", operationName, attempt + 1, delay, e.getMessage());
                    try { Thread.sleep(delay); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); throw e; }
                }
            }
        }
        throw new RuntimeException(operationName + " 重试" + maxRetries + "次后仍失败", lastException);
    }
}
