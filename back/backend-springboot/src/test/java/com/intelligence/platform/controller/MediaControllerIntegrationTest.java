package com.intelligence.platform.controller;

import com.intelligence.platform.entity.Document;
import com.intelligence.platform.mapper.DocumentMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MediaController 集成测试
 *
 * 测试目标：
 * 1. 验证 /api/media/pdf-cover/{docId} 端点逻辑正确
 * 2. 验证 /api/media/doc-file/{docId} 端点逻辑正确
 * 3. 验证 /api/media/{docId}/{filename} 端点逻辑正确
 *
 * 注意：这些测试使用真实数据库和文件系统，不使用 mock data
 * 测试会明确输出每一步的操作和结果
 */
@SpringBootTest
@DisplayName("MediaController 集成测试")
class MediaControllerIntegrationTest {

    @Autowired
    private MediaController mediaController;

    @Autowired
    private DocumentMapper documentMapper;

    @Value("${upload.dir:../uploads}")
    private String uploadDir;

    @Test
    @DisplayName("步骤1: 测试 pdf-cover - 找不到文档时应返回404")
    void testGetPdfCover_NonExistentDoc_Returns404() {
        System.out.println("=== 测试: pdf-cover 端点 - 找不到文档 ===");
        System.out.println("测试文档ID: 999999");
        System.out.println("预期结果: HTTP 404 Not Found");

        var response = mediaController.getPdfCover(999999L);

        assertNotNull(response, "Response 不应为 null");
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode(), "应返回 404");
        System.out.println("实际结果: HTTP " + response.getStatusCode().value());
        System.out.println("测试结果: PASS");
    }

    @Test
    @DisplayName("步骤2: 测试 doc-file - 找不到文档时应返回404")
    void testGetDocFile_NonExistentDoc_Returns404() {
        System.out.println("=== 测试: doc-file 端点 - 找不到文档 ===");
        System.out.println("测试文档ID: 999999");
        System.out.println("预期结果: HTTP 404 Not Found");

        var response = mediaController.getDocFile(999999L);

        assertNotNull(response, "Response 不应为 null");
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode(), "应返回 404");
        System.out.println("实际结果: HTTP " + response.getStatusCode().value());
        System.out.println("测试结果: PASS");
    }

    @Test
    @DisplayName("步骤3: 验证 pdf-cover 方法处理空文档路径")
    void testGetPdfCover_NullFilePath_Returns404() {
        System.out.println("=== 测试: pdf-cover - 文档路径为空 ===");

        // 创建一个测试文档，filePath 为 null
        Document doc = new Document();
        doc.setId(999998L);
        doc.setTitle("测试文档-空路径");
        doc.setFilePath(null);

        // 插入临时测试数据
        documentMapper.insert(doc);
        System.out.println("插入测试文档，ID: " + doc.getId());

        var response = mediaController.getPdfCover(doc.getId());
        assertNotNull(response, "Response 不应为 null");
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode(), "文档路径为空时应返回 404");
        System.out.println("实际结果: HTTP " + response.getStatusCode().value());

        // 清理测试数据
        documentMapper.deleteById(doc.getId());
        System.out.println("清理测试文档，ID: " + doc.getId());
        System.out.println("测试结果: PASS");
    }

    @Test
    @DisplayName("步骤4: 验证 uploadDir 配置正确加载")
    void testUploadDir_ConfigLoadedSuccessfully() {
        System.out.println("=== 测试: uploadDir 配置加载 ===");
        System.out.println("配置值: " + uploadDir);

        assertNotNull(uploadDir, "uploadDir 配置不应为 null");
        assertFalse(uploadDir.isEmpty(), "uploadDir 配置不应为空字符串");
        System.out.println("测试结果: PASS");
    }

    @Test
    @DisplayName("步骤5: 验证 media 端点 - 文件不存在时返回404")
    void testGetMedia_NonExistentFile_Returns404() {
        System.out.println("=== 测试: media 端点 - 文件不存在 ===");
        System.out.println("测试路径: /api/media/999999/nonexistent.png");

        var response = mediaController.getMedia("999999", "nonexistent.png");

        assertNotNull(response, "Response 不应为 null");
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode(), "文件不存在时应返回 404");
        System.out.println("实际结果: HTTP " + response.getStatusCode().value());
        System.out.println("测试结果: PASS");
    }
}
