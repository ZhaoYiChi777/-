package com.intelligence.platform.controller;

import com.intelligence.platform.service.VectorSearchService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 向量搜索控制器
 * 管理向量索引（FAISS IndexFlatIP 等价实现）
 */
@RestController
@RequestMapping("/api/vector")
@CrossOrigin(origins = "*")
public class VectorSearchController {

    @Autowired
    private VectorSearchService vectorSearchService;

    /**
     * 重建向量索引（从数据库加载所有已审核词条）
     */
    @PostMapping("/rebuild")
    public Map<String, Object> rebuildIndex() {
        int count = vectorSearchService.rebuildIndex();
        return Map.of(
                "message", "索引重建完成",
                "indexed_count", count
        );
    }

    /**
     * 获取向量索引统计信息
     */
    @GetMapping("/stats")
    public Map<String, Object> getStats() {
        return vectorSearchService.getStats();
    }

    /**
     * 语义搜索测试
     */
    @PostMapping("/search")
    public Map<String, Object> search(@RequestBody Map<String, Object> body) {
        String query = (String) body.getOrDefault("query", "");
        int topK = body.containsKey("topK") ? ((Number) body.get("topK")).intValue() : 5;
        float threshold = body.containsKey("threshold") ? ((Number) body.get("threshold")).floatValue() : 0.0f;
        String mediaType = (String) body.get("mediaType");

        if (query.isEmpty()) {
            return Map.of("results", java.util.List.of(), "message", "请输入搜索内容");
        }

        try {
            var results = vectorSearchService.searchWithFilter(query, topK, threshold, mediaType);
            return Map.of(
                    "results", results.stream().map(r -> Map.of(
                            "id", r.id(),
                            "score", r.score(),
                            "metadata", r.metadata()
                    )).toList(),
                    "count", results.size()
            );
        } catch (Exception e) {
            return Map.of(
                    "results", java.util.List.of(),
                    "error", e.getMessage()
            );
        }
    }
}
