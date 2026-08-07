package com.intelligence.platform.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.intelligence.platform.entity.KnowledgeEntry;
import com.intelligence.platform.entity.LlmConfig;
import com.intelligence.platform.mapper.KnowledgeEntryMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.scheduling.annotation.Async;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;

/**
 * 向量搜索服务
 *
 * 功能：
 * 1. 调用 Embedding API 生成文本向量
 * 2. 管理内存向量索引（FAISS IndexFlatIP 等价实现）
 * 3. 提供语义相似度搜索
 *
 * 参考 llm_wiki 的 embedding.ts
 */
@Service
public class VectorSearchService {

    private static final Logger log = LoggerFactory.getLogger(VectorSearchService.class);

    @Autowired
    private LlmService llmService;
    @Autowired
    private KnowledgeEntryMapper knowledgeEntryMapper;
    @Autowired
    private ProjectContext projectContext;

    @Autowired(required = false)
    private com.intelligence.platform.mapper.SettingMapper settingMapper;

    @Value("${upload.dir:./uploads}")
    private String uploadDir;

    /**
     * 获取实际生效的上传目录
     */
    private String getEffectiveUploadDir() {
        if (settingMapper != null) {
            try {
                com.intelligence.platform.entity.Setting setting = settingMapper.selectById("upload_dir");
                if (setting != null && setting.getValue() != null && !setting.getValue().isBlank()) {
                    return setting.getValue();
                }
            } catch (Exception ignored) {}
        }
        return uploadDir;
    }

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    private VectorIndex index;
    private volatile boolean indexReady = false;

    @PostConstruct
    public void init() {
        Path indexPath = Path.of(getEffectiveUploadDir(), "vector-index.json");
        index = new VectorIndex(indexPath);
        indexReady = true;
        log.info("向量搜索服务初始化完成，索引大小: {}", index.size());

        // 异步检查并清理已被从数据库删除的陈旧脏数据词条，保证向量索引与数据库的强一致性
        java.util.concurrent.CompletableFuture.runAsync(() -> {
            try {
                Thread.sleep(3000); // 等待系统完全就绪后执行
                cleanStaleIndexEntries();
            } catch (Exception e) {
                log.warn("执行向量索引无效条目清理时出现异常: {}", e.getMessage());
            }
        });
    }

    /**
     * 清理向量索引中存在，但在数据库中已经被物理删除的词条。
     * 避免了手动/自动同步时 expensive embedding API 调用，且保障了数据一致性。
     */
    private void cleanStaleIndexEntries() {
        if (index == null || index.size() == 0) return;
        log.info("开始检查并清理向量索引中的无效/已删除词条...");
        int removedCount = 0;
        List<Long> allIds = index.getAllIds();
        for (Long id : allIds) {
            try {
                if (knowledgeEntryMapper.selectById(id) == null) {
                    index.remove(id);
                    removedCount++;
                    log.info("已将无效/已删除的词条（ID: {}）从内存向量索引中清理", id);
                }
            } catch (Exception e) {
                log.warn("检查词条 ID: {} 状态失败: {}", id, e.getMessage());
            }
        }
        if (removedCount > 0) {
            index.saveToDisk();
            log.info("向量索引清理成功！共移除 {} 个已被删除的词条，当前有效索引大小: {}", removedCount, index.size());
        } else {
            log.info("向量索引状态良好，未检测到无效/已删除词条。");
        }
    }

    @PreDestroy
    public void shutdown() {
        if (index != null) {
            index.saveToDisk();
        }
    }

    /**
     * 生成文本的嵌入向量
     */
    public float[] generateEmbedding(String text) throws Exception {
        LlmConfig config = llmService.getActiveEmbeddingConfig();
        if (config == null) {
            throw new RuntimeException("未配置 Embedding 模型。请在系统配置中添加并启用 Embedding 配置。");
        }

        String url = resolveEmbeddingUrl(config);
        String body = buildEmbeddingBody(config, text);

        HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .version(HttpClient.Version.HTTP_1_1)
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(60))
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));

        String apiKey = config.getApiKey();
        if (apiKey != null && !apiKey.isEmpty()) {
            if ("google".equals(config.getProvider())) {
                reqBuilder.header("x-goog-api-key", apiKey);
            } else if (!"ollama".equals(config.getProvider())) {
                reqBuilder.header("Authorization", "Bearer " + apiKey);
            }
        }

        HttpResponse<String> response = httpClient.send(reqBuilder.build(),
                HttpResponse.BodyHandlers.ofString());

        return parseEmbeddingResponse(config, response.body());
    }

    /**
     * 将知识词条添加到向量索引
     */
    public void indexEntry(KnowledgeEntry entry) {
        try {
            // 组合标题、内容、关键词作为嵌入文本
            String text = buildEmbeddingText(entry);
            float[] vector = generateEmbedding(text);

            Map<String, String> meta = new HashMap<>();
            meta.put("title", entry.getTitle() != null ? entry.getTitle() : "");
            meta.put("content", entry.getContent() != null ? entry.getContent() : "");
            meta.put("library", entry.getEntryLibrary() != null ? entry.getEntryLibrary() : "");
            meta.put("sourceName", entry.getSourceName() != null ? entry.getSourceName() : "");
            meta.put("entryType", entry.getEntryType() != null ? entry.getEntryType() : "");
            meta.put("mediaType", entry.getMediaType() != null ? entry.getMediaType() : "text");
            meta.put("mediaPath", entry.getMediaPath() != null ? entry.getMediaPath() : "");
            meta.put("sourceOrigin", entry.getSourceOrigin() != null ? entry.getSourceOrigin() : "");
            meta.put("tableMarkdown", entry.getTableMarkdown() != null ? entry.getTableMarkdown() : "");
            meta.put("projectId", entry.getProjectId() != null ? String.valueOf(entry.getProjectId()) : "");

            index.add(entry.getId(), vector, meta);
            log.debug("已索引词条: id={}, title={}", entry.getId(), entry.getTitle());
        } catch (Exception e) {
            log.warn("索引词条失败: id={}, error={}", entry.getId(), e.getMessage());
        }
    }

    /**
     * 从向量索引中移除词条
     */
    public void removeEntry(long entryId) {
        index.remove(entryId);
    }

    /**
     * 语义搜索：返回最相似的知识词条
     */
    public List<VectorIndex.SearchResult> search(String query, int topK) throws Exception {
        if (index.size() == 0) {
            return List.of();
        }

        float[] queryVector = generateEmbedding(query);
        int candidateK = Math.min(topK * 15, index.size());
        List<VectorIndex.SearchResult> candidates = index.search(queryVector, candidateK);

        Long currentProjectId = projectContext.getCurrentProjectId();
        List<VectorIndex.SearchResult> filtered = candidates.stream()
                .filter(r -> {
                    if (currentProjectId == null) return true;
                    String entryProjectIdStr = r.metadata() != null ? r.metadata().get("projectId") : null;
                    if (entryProjectIdStr != null) {
                        try {
                            return currentProjectId.equals(Long.valueOf(entryProjectIdStr));
                        } catch (Exception ignored) {}
                    }
                    try {
                        KnowledgeEntry entry = knowledgeEntryMapper.selectById(r.id());
                        if (entry != null) {
                            if (r.metadata() != null) {
                                r.metadata().put("projectId", String.valueOf(entry.getProjectId()));
                            }
                            return currentProjectId.equals(entry.getProjectId());
                        }
                    } catch (Exception ignored) {}
                    return false;
                })
                .limit(topK)
                .toList();

        return filtered;
    }

    /**
     * 混合检索：Top-K + 阈值双重过滤 + 可选类型过滤
     * @param query 查询文本
     * @param topK 最大返回数量
     * @param threshold 最低相似度阈值（余弦相似度，0~1）
     * @param mediaType 可选媒体类型过滤（text/table/image，null则不过滤）
     * @return 过滤后的搜索结果
     */
    public List<VectorIndex.SearchResult> searchWithFilter(String query, int topK, float threshold, String mediaType) throws Exception {
        if (index.size() == 0) {
            return List.of();
        }

        float[] queryVector = generateEmbedding(query);
        Long currentProjectId = projectContext.getCurrentProjectId();

        // 构造 Pre-filtering 过滤器：在计算相似度阶段就把不属于该项目或类型不符的文档过滤掉
        java.util.function.Predicate<Map<String, String>> filter = meta -> {
            if (meta == null) return false;
            // 1. 项目隔离过滤
            if (currentProjectId != null) {
                String entryProjectIdStr = meta.get("projectId");
                if (entryProjectIdStr != null) {
                    try {
                        if (!currentProjectId.equals(Long.valueOf(entryProjectIdStr))) {
                            return false;
                        }
                    } catch (Exception e) {
                        return false;
                    }
                } else {
                    return false;
                }
            }
            // 2. 媒体类型过滤
            if (mediaType != null) {
                String type = meta.get("mediaType");
                if (!mediaType.equals(type)) {
                    return false;
                }
            }
            return true;
        };

        // 直接获取前 topK 个符合过滤条件的匹配项（避免 post-filtering 导致结果数量骤降甚至归零）
        List<VectorIndex.SearchResult> results = index.search(queryVector, topK, filter);

        // 应用阈值过滤并确保在 MySQL 数据库中仍然存在该实体记录（防止已删除的幽灵/脏数据条目被返回）
        List<VectorIndex.SearchResult> filtered = results.stream()
                .filter(r -> r.score() >= threshold)
                .filter(r -> {
                    try {
                        return knowledgeEntryMapper.selectById(r.id()) != null;
                    } catch (Exception e) {
                        return false;
                    }
                })
                .toList();

        log.info("向量检索 [{}] - Pre-filtering 后找到: {} 个, 相似度阈值过滤(>={})后返回: {} 个", 
                 mediaType, results.size(), threshold, filtered.size());

        if (log.isDebugEnabled()) {
            for (int i = 0; i < filtered.size(); i++) {
                var r = filtered.get(i);
                log.debug("  [{}] 分数={:.3f}, title={}", i + 1, r.score(), r.metadata().get("title"));
            }
        }

        log.info("向量搜索 [{}] - 阈值过滤后(>={}): {} 个", mediaType, threshold, filtered.size());

        // 4. 截取 topK
        List<VectorIndex.SearchResult> finalResults = filtered.stream().limit(topK).toList();
        log.info("向量搜索 [{}] - 最终返回: {} 个", mediaType, finalResults.size());
        return finalResults;
    }

    /**
     * 分类检索：分别返回文本、表格、图片结果
     */
    public Map<String, List<VectorIndex.SearchResult>> searchByType(String query, int textTopK, int tableTopK,
                                                                      int imageTopK, float threshold) throws Exception {
        if (index.size() == 0) {
            return Map.of("text", List.of(), "table", List.of(), "image", List.of());
        }

        float[] queryVector = generateEmbedding(query);
        int totalK = textTopK + tableTopK + imageTopK;
        List<VectorIndex.SearchResult> allResults = index.search(queryVector, totalK * 3);

        // 按类型分组，应用阈值过滤
        List<VectorIndex.SearchResult> textResults = allResults.stream()
                .filter(r -> r.score() >= threshold)
                .filter(r -> {
                    String type = r.metadata() != null ? r.metadata().get("mediaType") : null;
                    return type == null || "text".equals(type);
                })
                .limit(textTopK)
                .toList();

        List<VectorIndex.SearchResult> tableResults = allResults.stream()
                .filter(r -> r.score() >= threshold)
                .filter(r -> {
                    String type = r.metadata() != null ? r.metadata().get("mediaType") : null;
                    return "table".equals(type);
                })
                .limit(tableTopK)
                .toList();

        List<VectorIndex.SearchResult> imageResults = allResults.stream()
                .filter(r -> r.score() >= threshold)
                .filter(r -> {
                    String type = r.metadata() != null ? r.metadata().get("mediaType") : null;
                    return "image".equals(type);
                })
                .limit(imageTopK)
                .toList();

        return Map.of("text", textResults, "table", tableResults, "image", imageResults);
    }

    /**
     * 批量重建索引（从数据库中加载所有已审核词条）
     * 异步执行，避免阻塞主线程
     */
    @Async("taskExecutor")
    public void rebuildIndexAsync() {
        log.info("异步重建索引开始...");
        try {
            int count = rebuildIndexSync();
            log.info("异步重建索引完成: 共 {} 个词条", count);
        } catch (Exception e) {
            log.error("异步重建索引失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 同步重建索引（内部方法）
     */
    private int rebuildIndexSync() {
        index.clear(); // 清空内存中的旧索引，防止已在数据库中删除的脏数据残留
        List<KnowledgeEntry> entries = knowledgeEntryMapper.selectList(
                new LambdaQueryWrapper<KnowledgeEntry>()
                        .in(KnowledgeEntry::getStatus, "approved", "pending"));

        int count = 0;
        for (KnowledgeEntry entry : entries) {
            try {
                indexEntry(entry);
                count++;
            } catch (Exception e) {
                log.warn("重建索引时跳过词条 {}: {}", entry.getId(), e.getMessage());
            }
        }

        index.saveToDisk();
        log.info("索引重建完成: 共 {} 个词条", count);
        return count;
    }

    /**
     * 批量重建索引（同步，兼容旧接口）
     */
    public int rebuildIndex() {
        return rebuildIndexSync();
    }

    /**
     * 获取索引统计信息
     */
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("size", index.size());
        stats.put("dimension", index.getDimension());
        stats.put("hasEmbeddingConfig", llmService.getActiveEmbeddingConfig() != null);
        stats.put("ready", indexReady);
        return stats;
    }

    /**
     * 检查向量索引是否已就绪
     */
    public boolean isIndexReady() {
        return indexReady;
    }

    // ==================== Embedding API 调用 ====================

    private String buildEmbeddingText(KnowledgeEntry entry) {
        StringBuilder sb = new StringBuilder();
        if (entry.getTitle() != null) sb.append(entry.getTitle()).append(". ");
        if (entry.getContent() != null) sb.append(entry.getContent()).append(" ");
        if (entry.getKeywords() != null) sb.append(entry.getKeywords());
        return sb.toString().trim();
    }

    private String resolveEmbeddingUrl(LlmConfig config) {
        if ("custom".equals(config.getProvider())) {
            String base = config.getBaseUrl() != null ? config.getBaseUrl() : "http://localhost:11434";
            return base.endsWith("/embeddings") ? base : base + "/embeddings";
        }
        return switch (config.getProvider()) {
            case "openai" -> "https://api.openai.com/v1/embeddings";
            case "ollama" -> {
                String base = config.getBaseUrl() != null ? config.getBaseUrl() : "http://localhost:11434";
                yield base + "/api/embeddings";
            }
            case "google" -> "https://generativelanguage.googleapis.com/v1beta/models/"
                    + config.getModel() + ":embedContent";
            case "siliconflow" -> {
                String base = config.getBaseUrl() != null ? config.getBaseUrl() : "https://api.siliconflow.cn/v1";
                yield base + "/embeddings";
            }
            default -> config.getBaseUrl() != null
                    ? (config.getBaseUrl().endsWith("/embeddings") ? config.getBaseUrl() : config.getBaseUrl() + "/embeddings")
                    : "http://localhost:11434/api/embeddings";
        };
    }

    private String buildEmbeddingBody(LlmConfig config, String text) throws Exception {
        if ("google".equals(config.getProvider())) {
            ObjectNode root = mapper.createObjectNode();
            root.putObject("content").putArray("parts").addObject().put("text", text);
            return mapper.writeValueAsString(root);
        }

        // OpenAI / Ollama / Custom 兼容格式
        ObjectNode root = mapper.createObjectNode();
        root.put("model", config.getModel());
        if ("ollama".equals(config.getProvider())) {
            root.put("prompt", text);
        } else {
            root.put("input", text);
        }
        return mapper.writeValueAsString(root);
    }

    private float[] parseEmbeddingResponse(LlmConfig config, String responseBody) throws Exception {
        JsonNode root = mapper.readTree(responseBody);

        // Google 格式
        if (root.has("embedding")) {
            JsonNode emb = root.get("embedding");
            if (emb.has("values")) {
                return jsonArrayToFloatArray(emb.get("values"));
            }
            return jsonArrayToFloatArray(emb);
        }

        // Ollama 格式
        if (root.has("embedding")) {
            return jsonArrayToFloatArray(root.get("embedding"));
        }

        // OpenAI 格式
        if (root.has("data") && root.get("data").isArray() && !root.get("data").isEmpty()) {
            JsonNode first = root.get("data").get(0);
            if (first.has("embedding")) {
                return jsonArrayToFloatArray(first.get("embedding"));
            }
        }

        throw new RuntimeException("无法解析 Embedding 响应: " + responseBody.substring(0, Math.min(200, responseBody.length())));
    }

    private float[] jsonArrayToFloatArray(JsonNode arr) {
        if (!arr.isArray()) {
            throw new RuntimeException("期望数组，实际: " + arr.getNodeType());
        }
        float[] result = new float[arr.size()];
        for (int i = 0; i < arr.size(); i++) {
            result[i] = (float) arr.get(i).asDouble();
        }
        return result;
    }
}
