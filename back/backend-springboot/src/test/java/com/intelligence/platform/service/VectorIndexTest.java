package com.intelligence.platform.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * VectorIndex 单元测试
 * 测试向量索引的核心功能：添加、搜索、删除、持久化
 */
class VectorIndexTest {

    @TempDir
    Path tempDir;

    private VectorIndex index;
    private Path indexPath;

    @BeforeEach
    void setUp() {
        indexPath = tempDir.resolve("test-index.json");
        index = new VectorIndex(indexPath);
    }

    @Test
    void testAddAndSize() {
        assertEquals(0, index.size());

        float[] vec1 = {1.0f, 0.0f, 0.0f};
        Map<String, String> meta1 = Map.of("title", "Test 1");
        index.add(1L, vec1, meta1);

        assertEquals(1, index.size());
        assertEquals(3, index.getDimension());

        float[] vec2 = {0.0f, 1.0f, 0.0f};
        Map<String, String> meta2 = Map.of("title", "Test 2");
        index.add(2L, vec2, meta2);

        assertEquals(2, index.size());
    }

    @Test
    void testRemove() {
        float[] vec = {1.0f, 0.0f, 0.0f};
        Map<String, String> meta = Map.of("title", "Test");
        index.add(1L, vec, meta);
        assertEquals(1, index.size());

        index.remove(1L);
        assertEquals(0, index.size());
    }

    @Test
    void testSearchEmptyIndex() {
        float[] query = {1.0f, 0.0f, 0.0f};
        List<VectorIndex.SearchResult> results = index.search(query, 5);
        assertTrue(results.isEmpty());
    }

    @Test
    void testSearchSingleResult() {
        float[] vec = {1.0f, 0.0f, 0.0f};
        Map<String, String> meta = Map.of("title", "Test Document");
        index.add(1L, vec, meta);

        float[] query = {1.0f, 0.0f, 0.0f};
        List<VectorIndex.SearchResult> results = index.search(query, 5);

        assertEquals(1, results.size());
        assertEquals(1L, results.get(0).id());
        assertEquals(1.0f, results.get(0).score(), 0.001f);
        assertEquals("Test Document", results.get(0).metadata().get("title"));
    }

    @Test
    void testSearchCosineSimilarity() {
        // 添加三个向量，测试余弦相似度排序
        float[] vec1 = {1.0f, 0.0f, 0.0f};
        float[] vec2 = {0.0f, 1.0f, 0.0f};
        float[] vec3 = {0.707f, 0.707f, 0.0f}; // 45度角

        index.add(1L, vec1, Map.of("title", "Vec 1"));
        index.add(2L, vec2, Map.of("title", "Vec 2"));
        index.add(3L, vec3, Map.of("title", "Vec 3"));

        // 查询向量，应该与 vec1 最相似
        float[] query = {0.9f, 0.1f, 0.0f};
        List<VectorIndex.SearchResult> results = index.search(query, 3);

        assertEquals(3, results.size());
        // 第一个应该是 vec1（最相似）
        assertEquals(1L, results.get(0).id());
        // 分数应该按降序排列
        assertTrue(results.get(0).score() >= results.get(1).score());
        assertTrue(results.get(1).score() >= results.get(2).score());
    }

    @Test
    void testSearchTopK() {
        // 添加5个向量
        for (int i = 0; i < 5; i++) {
            float[] vec = new float[3];
            vec[i % 3] = 1.0f;
            index.add((long) i, vec, Map.of("title", "Vec " + i));
        }

        // 查询 top 2
        float[] query = {1.0f, 0.0f, 0.0f};
        List<VectorIndex.SearchResult> results = index.search(query, 2);

        assertEquals(2, results.size());
    }

    @Test
    void testDimensionMismatch() {
        float[] vec1 = {1.0f, 0.0f, 0.0f};
        index.add(1L, vec1, Map.of("title", "Test 1"));
        assertEquals(3, index.getDimension());

        // 添加维度不匹配的向量，应该被忽略
        float[] vec2 = {1.0f, 0.0f};
        index.add(2L, vec2, Map.of("title", "Test 2"));

        // 只有第一个向量被添加
        assertEquals(1, index.size());
    }

    @Test
    void testPersistence() {
        // 添加向量
        float[] vec1 = {1.0f, 0.0f, 0.0f};
        float[] vec2 = {0.0f, 1.0f, 0.0f};
        index.add(1L, vec1, Map.of("title", "Test 1"));
        index.add(2L, vec2, Map.of("title", "Test 2"));

        // 保存到磁盘
        index.saveToDisk();
        assertTrue(indexPath.toFile().exists());

        // 创建新索引并从磁盘加载
        VectorIndex loadedIndex = new VectorIndex(indexPath);

        assertEquals(2, loadedIndex.size());
        assertEquals(3, loadedIndex.getDimension());

        // 搜索应该返回相同结果
        float[] query = {1.0f, 0.0f, 0.0f};
        List<VectorIndex.SearchResult> results = loadedIndex.search(query, 5);

        assertEquals(2, results.size());
    }

    @Test
    void testL2Normalization() {
        // 测试 L2 归一化是否正确
        float[] vec1 = {2.0f, 0.0f, 0.0f}; // 长度为2
        float[] vec2 = {1.0f, 0.0f, 0.0f}; // 长度为1

        index.add(1L, vec1, Map.of("title", "Vec 1"));
        index.add(2L, vec2, Map.of("title", "Vec 2"));

        // 查询应该返回相同结果（因为都被归一化了）
        float[] query = {3.0f, 0.0f, 0.0f};
        List<VectorIndex.SearchResult> results = index.search(query, 2);

        assertEquals(2, results.size());
        // 两个结果应该有相同的分数（都是1.0）
        assertEquals(results.get(0).score(), results.get(1).score(), 0.001f);
    }
}
