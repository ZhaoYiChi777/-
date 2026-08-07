package com.intelligence.platform.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存向量索引（FAISS IndexFlatIP 等价实现）
 *
 * 使用暴力搜索（brute-force）计算余弦相似度，适用于中小规模数据集（<10万向量）。
 * 对于本项目规模（数百到数千词条），性能与 FAISS 相当甚至更快（无 JNI 开销）。
 *
 * 持久化：索引序列化为 JSON 文件存储，启动时自动加载。
 */
public class VectorIndex {

    private static final Logger log = LoggerFactory.getLogger(VectorIndex.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    /** 向量ID → 向量 */
    private final ConcurrentHashMap<Long, float[]> vectors = new ConcurrentHashMap<>();
    /** 向量ID → 元数据（标题、内容等） */
    private final ConcurrentHashMap<Long, Map<String, String>> metadata = new ConcurrentHashMap<>();
    /** 向量维度 */
    private int dimension = 0;
    /** 持久化文件路径 */
    private final Path persistPath;

    public VectorIndex(Path persistPath) {
        this.persistPath = persistPath;
        loadFromDisk();
    }

    /**
     * 添加向量到索引
     */
    public synchronized void add(long id, float[] vector, Map<String, String> meta) {
        if (dimension == 0) {
            dimension = vector.length;
        } else if (vector.length != dimension) {
            log.warn("向量维度不匹配: 期望 {}, 实际 {}", dimension, vector.length);
            return;
        }
        // L2 归一化（使内积等价于余弦相似度）
        float[] normalized = l2Normalize(vector);
        vectors.put(id, normalized);
        metadata.put(id, meta);
    }

    /**
     * 从索引中移除向量
     */
    public synchronized void remove(long id) {
        vectors.remove(id);
        metadata.remove(id);
    }

    /**
     * 清空索引中的所有向量和元数据
     */
    public synchronized void clear() {
        vectors.clear();
        metadata.clear();
        dimension = 0;
    }

    /**
     * 搜索最相似的 topK 个向量
     * @param query 查询向量
     * @param topK 返回数量
     * @return 搜索结果列表（id, score, metadata）
     */
    public List<SearchResult> search(float[] query, int topK) {
        if (vectors.isEmpty() || dimension == 0) {
            return List.of();
        }

        float[] normalizedQuery = l2Normalize(query);
        PriorityQueue<SearchResult> heap = new PriorityQueue<>(
                Comparator.comparingDouble(r -> r.score));

        for (Map.Entry<Long, float[]> entry : vectors.entrySet()) {
            long id = entry.getKey();
            float[] vec = entry.getValue();
            float score = dotProduct(normalizedQuery, vec); // 余弦相似度（已归一化）

            if (heap.size() < topK) {
                heap.add(new SearchResult(id, score, metadata.get(id)));
            } else if (score > heap.peek().score) {
                heap.poll();
                heap.add(new SearchResult(id, score, metadata.get(id)));
            }
        }

        // 按分数降序排列
        List<SearchResult> results = new ArrayList<>(heap);
        results.sort((a, b) -> Float.compare(b.score, a.score));
        return results;
    }

    /**
     * 搜索最相似的 topK 个向量（支持传入自定义过滤器，实现 Pre-filtering 从而避免 Post-filtering 丢结果的问题）
     * @param query 查询向量
     * @param topK 返回数量
     * @param filter 过滤器
     * @return 搜索结果列表（id, score, metadata）
     */
    public List<SearchResult> search(float[] query, int topK, java.util.function.Predicate<Map<String, String>> filter) {
        if (vectors.isEmpty() || dimension == 0) {
            return List.of();
        }

        float[] normalizedQuery = l2Normalize(query);
        PriorityQueue<SearchResult> heap = new PriorityQueue<>(
                Comparator.comparingDouble(r -> r.score));

        for (Map.Entry<Long, float[]> entry : vectors.entrySet()) {
            long id = entry.getKey();
            Map<String, String> meta = metadata.get(id);
            if (filter != null && !filter.test(meta)) {
                continue;
            }
            float[] vec = entry.getValue();
            float score = dotProduct(normalizedQuery, vec);

            if (heap.size() < topK) {
                heap.add(new SearchResult(id, score, meta));
            } else if (score > heap.peek().score) {
                heap.poll();
                heap.add(new SearchResult(id, score, meta));
            }
        }

        List<SearchResult> results = new ArrayList<>(heap);
        results.sort((a, b) -> Float.compare(b.score, a.score));
        return results;
    }

    /**
     * 获取索引中的向量数量
     */
    public int size() {
        return vectors.size();
    }

    /**
     * 获取索引中所有向量的ID列表（用于无效条目清理）
     */
    public List<Long> getAllIds() {
        return new ArrayList<>(vectors.keySet());
    }

    /**
     * 获取向量维度
     */
    public int getDimension() {
        return dimension;
    }

    /**
     * 持久化索引到磁盘
     */
    public synchronized void saveToDisk() {
        try {
            ObjectNode root = mapper.createObjectNode();
            root.put("dimension", dimension);

            ArrayNode entries = root.putArray("entries");
            for (Map.Entry<Long, float[]> entry : vectors.entrySet()) {
                ObjectNode node = entries.addObject();
                node.put("id", entry.getKey());

                ArrayNode vecArr = node.putArray("vector");
                for (float v : entry.getValue()) {
                    vecArr.add(v);
                }

                ObjectNode meta = node.putObject("metadata");
                Map<String, String> m = metadata.get(entry.getKey());
                if (m != null) {
                    m.forEach(meta::put);
                }
            }

            Files.createDirectories(persistPath.getParent());
            Files.writeString(persistPath, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root));
            log.info("向量索引已保存: {} 个向量, 维度 {}", vectors.size(), dimension);
        } catch (IOException e) {
            log.error("保存向量索引失败: {}", e.getMessage());
        }
    }

    /**
     * 从磁盘加载索引
     */
    private void loadFromDisk() {
        if (!Files.exists(persistPath)) {
            log.info("向量索引文件不存在，使用空索引: {}", persistPath);
            return;
        }

        try {
            String json = Files.readString(persistPath);
            var root = mapper.readTree(json);

            dimension = root.has("dimension") ? root.get("dimension").asInt() : 0;

            if (root.has("entries")) {
                for (var entry : root.get("entries")) {
                    long id = entry.get("id").asLong();

                    var vecArr = entry.get("vector");
                    float[] vec = new float[vecArr.size()];
                    for (int i = 0; i < vecArr.size(); i++) {
                        vec[i] = (float) vecArr.get(i).asDouble();
                    }

                    Map<String, String> meta = new HashMap<>();
                    if (entry.has("metadata")) {
                        var metaNode = entry.get("metadata");
                        metaNode.fields().forEachRemaining(f -> {
                            String key = f.getKey();
                            String value = f.getValue().asText();
                            meta.put(key, value);
                        });
                    }

                    vectors.put(id, vec);
                    metadata.put(id, meta);
                }
            }

            log.info("向量索引已加载: {} 个向量, 维度 {}", vectors.size(), dimension);
            // 调试：检查第一个条目的元数据
            if (!metadata.isEmpty()) {
                Long firstId = metadata.keySet().iterator().next();
                log.debug("第一个条目的元数据: id={}, metadata={}", firstId, metadata.get(firstId));
            }
        } catch (IOException e) {
            log.error("加载向量索引失败: {}", e.getMessage());
        }
    }

    /**
     * L2 归一化
     */
    private static float[] l2Normalize(float[] vec) {
        float norm = 0;
        for (float v : vec) norm += v * v;
        norm = (float) Math.sqrt(norm);
        if (norm < 1e-10f) return vec;

        float[] result = new float[vec.length];
        for (int i = 0; i < vec.length; i++) {
            result[i] = vec[i] / norm;
        }
        return result;
    }

    /**
     * 点积（内积）
     */
    private static float dotProduct(float[] a, float[] b) {
        float sum = 0;
        int len = Math.min(a.length, b.length);
        for (int i = 0; i < len; i++) {
            sum += a[i] * b[i];
        }
        return sum;
    }

    /**
     * 搜索结果
     */
    public record SearchResult(long id, float score, Map<String, String> metadata) {}
}
