package com.intelligence.platform.controller;

import com.intelligence.platform.entity.KnowledgeEntry;
import com.intelligence.platform.entity.Document;
import com.intelligence.platform.mapper.KnowledgeEntryMapper;
import com.intelligence.platform.mapper.DocumentMapper;
import com.intelligence.platform.service.ImageService;
import com.intelligence.platform.service.ProjectContext;
import com.intelligence.platform.service.VectorSearchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 媒体文件访问控制器
 * 提供图片等媒体文件的HTTP访问接口 + OCR/合并功能
 */
@RestController
@RequestMapping("/api/media")
@CrossOrigin(origins = "*")
public class MediaController {

    private static final Logger log = LoggerFactory.getLogger(MediaController.class);

    @Value("${upload.dir:./uploads}")
    private String uploadDir;

    @Autowired(required = false)
    private com.intelligence.platform.mapper.SettingMapper settingMapper;

    /**
     * 获取实际生效的上传目录（优先从数据库读取，回退到配置文件）
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

    @Autowired
    private ImageService imageService;
    @Autowired
    private KnowledgeEntryMapper knowledgeEntryMapper;
    @Autowired
    private VectorSearchService vectorSearchService;
    @Autowired
    private ProjectContext projectContext;
    @Autowired
    private DocumentMapper documentMapper;

    /**
     * 获取媒体文件（图片等）
     * 路径格式: /api/media/{docId}/{filename}
     */
    @GetMapping("/{docId}/{filename}")
    public ResponseEntity<Resource> getMedia(@PathVariable String docId, @PathVariable String filename) {
        try {
            Path filePath = Paths.get(getEffectiveUploadDir(), "media", docId, filename).toAbsolutePath().normalize();
            log.debug("媒体请求: /api/media/{}/{} -> 解析路径: {}", docId, filename, filePath);

            if (!Files.exists(filePath)) {
                log.warn("媒体文件不存在: {} (请求: {}/{})", filePath, docId, filename);
                return ResponseEntity.notFound().build();
            }

            Resource resource = new FileSystemResource(filePath);
            String contentType = Files.probeContentType(filePath);
            if (contentType == null || contentType.equals("application/octet-stream")) {
                // 基于文件扩展名的备用 content-type 检测
                String ext = getExtension(filename);
                contentType = getMimeType(ext);
            }

            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(contentType))
                    .header(HttpHeaders.CACHE_CONTROL, "max-age=86400")
                    .header(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "*")
                    .body(resource);
        } catch (Exception e) {
            log.error("媒体文件读取失败: {}/{} - {}", docId, filename, e.getMessage());
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * OCR识别图片中的表格 → 返回 Markdown 表格
     * POST /api/media/ocr-table
     */
    @PostMapping("/ocr-table")
    public Map<String, Object> ocrTable(@RequestParam("file") MultipartFile file) {
        try {
            byte[] data = file.getBytes();
            String ext = getExtension(file.getOriginalFilename());
            String mime = getMimeType(ext);
            String markdown = imageService.ocrTableImage(data, mime);
            return Map.of("status", "success", "markdown", markdown);
        } catch (Exception e) {
            return Map.of("status", "error", "markdown", "", "message", "OCR失败: " + e.getMessage());
        }
    }

    /**
     * 合并多个已有的表格图片条目为一个合并后的 Markdown 表格
     * POST /api/media/merge-tables
     * body: { "entryIds": [1, 2, 3], "title": "合并表格标题" }
     */
    @PostMapping("/merge-tables")
    public Map<String, Object> mergeTables(@RequestBody Map<String, Object> body) {
        try {
            @SuppressWarnings("unchecked")
            List<Number> entryIds = (List<Number>) body.get("entryIds");
            String title = (String) body.getOrDefault("title", "合并表格");

            if (entryIds == null || entryIds.isEmpty()) {
                return Map.of("status", "error", "message", "请选择至少一个表格条目");
            }

            // 获取各条目的 tableMarkdown
            List<String> markdownParts = new ArrayList<>();
            for (Number id : entryIds) {
                KnowledgeEntry entry = knowledgeEntryMapper.selectById(id.longValue());
                if (entry != null && entry.getTableMarkdown() != null && !entry.getTableMarkdown().isEmpty()) {
                    markdownParts.add(entry.getTableMarkdown());
                } else if (entry != null && entry.getMediaPath() != null) {
                    // 如果没有 tableMarkdown，尝试 OCR
                    Path imgPath = Path.of(entry.getMediaPath());
                    if (Files.exists(imgPath)) {
                        byte[] imgData = Files.readAllBytes(imgPath);
                        String ext = getExtension(entry.getMediaPath());
                        String mime = getMimeType(ext);
                        String ocr = imageService.ocrTableImage(imgData, mime);
                        markdownParts.add(ocr);
                    }
                }
            }

            if (markdownParts.isEmpty()) {
                return Map.of("status", "error", "message", "没有可合并的表格内容");
            }

            // 合并
            String mergedMarkdown = imageService.mergeMarkdownTables(markdownParts);

            // 创建合并后的新词条
            KnowledgeEntry mergedEntry = new KnowledgeEntry();
            mergedEntry.setTitle(title);
            mergedEntry.setEntryType("table");
            mergedEntry.setEntryLibrary("chart");
            mergedEntry.setSourceName("合并自 " + entryIds.size() + " 张表格图片");
            mergedEntry.setContent("合并表格数据（Markdown格式）");
            mergedEntry.setKeywords("表格,合并,OCR");
            mergedEntry.setStatus("pending");
            mergedEntry.setConfidence(0.85);
            mergedEntry.setCreatedAt(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
            mergedEntry.setMediaType("table");
            mergedEntry.setTableMarkdown(mergedMarkdown);
            mergedEntry.setProjectId(projectContext.getCurrentProjectId());
            knowledgeEntryMapper.insert(mergedEntry);
            vectorSearchService.indexEntry(mergedEntry);

            return Map.of(
                    "status", "success",
                    "markdown", mergedMarkdown,
                    "entry_id", mergedEntry.getId(),
                    "message", "已合并 " + markdownParts.size() + " 个表格片段"
            );
        } catch (Exception e) {
            log.warn("合并表格失败: {}", e.getMessage());
            return Map.of("status", "error", "message", "合并失败: " + e.getMessage());
        }
    }

    /**
     * 将已有的图片条目通过 OCR 转为表格条目
     * POST /api/media/ocr-existing
     * body: { "entryIds": [1, 2, 3] }
     */
    @PostMapping("/ocr-existing")
    public Map<String, Object> ocrExistingEntries(@RequestBody Map<String, Object> body) {
        try {
            @SuppressWarnings("unchecked")
            List<Number> entryIds = (List<Number>) body.get("entryIds");
            if (entryIds == null || entryIds.isEmpty()) {
                return Map.of("status", "error", "message", "请选择至少一个图片条目");
            }

            List<Map<String, Object>> results = new ArrayList<>();
            for (Number idNum : entryIds) {
                Long id = idNum.longValue();
                KnowledgeEntry entry = knowledgeEntryMapper.selectById(id);
                if (entry == null || entry.getMediaPath() == null) {
                    results.add(Map.of("id", id, "status", "error", "message", "条目不存在或无媒体路径"));
                    continue;
                }

                Path imgPath = Path.of(entry.getMediaPath());
                if (!Files.exists(imgPath)) {
                    results.add(Map.of("id", id, "status", "error", "message", "图片文件不存在"));
                    continue;
                }

                byte[] imgData = Files.readAllBytes(imgPath);
                String ext = getExtension(entry.getMediaPath());
                String mime = getMimeType(ext);

                // OCR 识别表格
                String tableMarkdown = imageService.ocrTableImage(imgData, mime);

                // 更新原条目：设置 tableMarkdown，改 mediaType 为 table
                entry.setTableMarkdown(tableMarkdown);
                entry.setMediaType("table");
                entry.setEntryType("table");
                entry.setKeywords((entry.getKeywords() != null ? entry.getKeywords() + "," : "") + "表格,OCR");
                knowledgeEntryMapper.updateById(entry);
                vectorSearchService.indexEntry(entry);

                results.add(Map.of("id", id, "status", "success", "markdown", tableMarkdown));
            }

            return Map.of("status", "success", "results", results, "message",
                    "已处理 " + results.size() + " 个条目");
        } catch (Exception e) {
            return Map.of("status", "error", "message", "OCR处理失败: " + e.getMessage());
        }
    }

    /**
     * 批量重新处理失败的图片条目（重新生成VLM描述）
     * POST /api/media/reprocess-images
     * body: { "entryIds": [1,2,3] } 或不传则处理所有失败条目
     */
    @PostMapping("/reprocess-images")
    public Map<String, Object> reprocessImages(@RequestBody(required = false) Map<String, Object> body) {
        try {
            List<Long> entryIds = new ArrayList<>();

            if (body != null && body.containsKey("entryIds")) {
                @SuppressWarnings("unchecked")
                List<Number> ids = (List<Number>) body.get("entryIds");
                if (ids != null) {
                    for (Number id : ids) entryIds.add(id.longValue());
                }
            }

            // 如果没有指定ID，查找所有失败条目
            if (entryIds.isEmpty()) {
                List<KnowledgeEntry> allEntries = knowledgeEntryMapper.selectList(null);
                for (KnowledgeEntry e : allEntries) {
                    if ("image".equals(e.getMediaType()) && e.getContent() != null
                            && e.getContent().contains("失败")) {
                        entryIds.add(e.getId());
                    }
                }
            }

            if (entryIds.isEmpty()) {
                return Map.of("status", "success", "message", "没有需要重新处理的条目", "processed", 0);
            }

            List<Map<String, Object>> results = new ArrayList<>();
            for (Long id : entryIds) {
                KnowledgeEntry entry = knowledgeEntryMapper.selectById(id);
                if (entry == null || entry.getMediaPath() == null) {
                    results.add(Map.of("id", id, "status", "skipped", "message", "条目不存在或无媒体路径"));
                    continue;
                }

                Path imgPath = Path.of(entry.getMediaPath());
                if (!Files.exists(imgPath)) {
                    results.add(Map.of("id", id, "status", "skipped", "message", "图片文件不存在"));
                    continue;
                }

                try {
                    byte[] imgData = Files.readAllBytes(imgPath);
                    String ext = getExtension(entry.getMediaPath());
                    String mime = getMimeType(ext);

                    // 重新生成 caption
                    String caption = imageService.generateCaption(imgData, mime);
                    entry.setContent(caption);
                    entry.setStatus("pending");
                    knowledgeEntryMapper.updateById(entry);
                    vectorSearchService.indexEntry(entry);

                    results.add(Map.of("id", id, "title", entry.getTitle(), "status", "success"));
                } catch (Exception e) {
                    results.add(Map.of("id", id, "status", "error", "message", e.getMessage()));
                }
            }

            long successCount = results.stream()
                    .filter(r -> "success".equals(r.get("status"))).count();
            return Map.of("status", "success", "results", results,
                    "message", "重新处理完成: " + successCount + "/" + entryIds.size());
        } catch (Exception e) {
            return Map.of("status", "error", "message", "重新处理失败: " + e.getMessage());
        }
    }

    @GetMapping("/pdf-cover/{docId}")
    public ResponseEntity<Resource> getPdfCover(@PathVariable Long docId) {
        try {
            Document doc = documentMapper.selectById(docId);
            if (doc == null || doc.getFilePath() == null) {
                log.warn("getPdfCover: 找不到文档或路径为空: {}", docId);
                return ResponseEntity.notFound().build();
            }

            // filePath 存储的是绝对路径，直接使用
            Path pdfPath = Paths.get(doc.getFilePath()).toAbsolutePath().normalize();
            if (!Files.exists(pdfPath) || !pdfPath.toString().toLowerCase().endsWith(".pdf")) {
                log.warn("getPdfCover: PDF文件不存在或格式不正确: {}", pdfPath);
                return ResponseEntity.notFound().build();
            }

            // 封面图片保存路径
            Path coverPath = Paths.get(getEffectiveUploadDir(), "media", String.valueOf(docId), "cover.png").toAbsolutePath().normalize();
            
            // 如果不存在封面，调用 Python 脚本生成
            if (!Files.exists(coverPath)) {
                Files.createDirectories(coverPath.getParent());
                String scriptPath = Paths.get("render_pdf_cover.py").toAbsolutePath().toString();
                log.info("getPdfCover: 开始生成 PDF 封面. pdfPath={}, scriptPath={}", pdfPath, scriptPath);
                ProcessBuilder pb = new ProcessBuilder(
                        "python3", 
                        scriptPath,
                        pdfPath.toString(),
                        coverPath.toString()
                );
                pb.redirectErrorStream(true);
                Process process = pb.start();
                int exitCode = process.waitFor();
                if (exitCode != 0) {
                    log.error("生成 PDF 封面失败，退出码: {}", exitCode);
                    return ResponseEntity.notFound().build();
                }
            }

            Resource resource = new FileSystemResource(coverPath);
            return ResponseEntity.ok()
                    .contentType(MediaType.IMAGE_PNG)
                    .header(HttpHeaders.CACHE_CONTROL, "max-age=86400")
                    .header(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "*")
                    .body(resource);
        } catch (Exception e) {
            log.error("读取 PDF 封面失败: docId={} - {}", docId, e.getMessage());
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * 根据文档ID直接serve原始文件（图片、PDF等）
     * 路径格式: /api/media/doc-file/{docId}
     */
    @GetMapping("/doc-file/{docId}")
    public ResponseEntity<Resource> getDocFile(@PathVariable Long docId) {
        try {
            Document doc = documentMapper.selectById(docId);
            if (doc == null || doc.getFilePath() == null) {
                log.warn("getDocFile: 找不到文档或路径为空: {}", docId);
                return ResponseEntity.notFound().build();
            }

            // filePath 存储的是绝对路径，如 /Users/.../uploads/raw/sources/图片/xxx.png
            // 直接使用，不需要拼接 uploadDir
            Path filePath = Paths.get(doc.getFilePath()).toAbsolutePath().normalize();
            if (!Files.exists(filePath)) {
                log.warn("getDocFile: 文件不存在: {} (docId={})", filePath, docId);
                return ResponseEntity.notFound().build();
            }

            Resource resource = new FileSystemResource(filePath);
            String ext = getExtension(filePath.getFileName().toString());
            String contentType = Files.probeContentType(filePath);
            if (isTextFileExtension(ext)) {
                contentType = "text/plain;charset=UTF-8";
            } else if (contentType == null || contentType.equals("application/octet-stream")) {
                contentType = getMimeType(ext);
            }

            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(contentType))
                    .header(HttpHeaders.CACHE_CONTROL, "max-age=86400")
                    .header(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "*")
                    .body(resource);
        } catch (Exception e) {
            log.error("读取文档文件失败: docId={} - {}", docId, e.getMessage());
            return ResponseEntity.internalServerError().build();
        }
    }

    private String getExtension(String filename) {
        if (filename == null) return "png";
        int dot = filename.lastIndexOf('.');
            return dot > 0 ? filename.substring(dot + 1).toLowerCase() : "png";
    }

    private boolean isTextFileExtension(String ext) {
        return switch (ext) {
            case "txt", "md", "csv", "json", "xml", "log", "properties", "yaml", "yml" -> true;
            default -> false;
        };
    }

    private String getMimeType(String ext) {
        return switch (ext) {
            case "jpg", "jpeg" -> "image/jpeg";
            case "png" -> "image/png";
            case "gif" -> "image/gif";
            case "webp" -> "image/webp";
            case "bmp" -> "image/bmp";
            default -> "image/png";
        };
    }
}
