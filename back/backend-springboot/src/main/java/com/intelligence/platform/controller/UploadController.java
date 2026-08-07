package com.intelligence.platform.controller;

import com.intelligence.platform.entity.Document;
import com.intelligence.platform.entity.KnowledgeEntry;
import com.intelligence.platform.mapper.DocumentMapper;
import com.intelligence.platform.mapper.KnowledgeEntryMapper;
import com.intelligence.platform.service.DocumentParseService;
import com.intelligence.platform.service.FileValidationService;
import com.intelligence.platform.service.UploadTaskService;
import com.intelligence.platform.service.VectorSearchService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/upload")
@CrossOrigin(origins = "*")
public class UploadController {

    @Autowired
    private DocumentMapper documentMapper;

    @Autowired
    private KnowledgeEntryMapper knowledgeEntryMapper;

    @Autowired
    private DocumentParseService documentParseService;

    @Autowired
    private FileValidationService fileValidationService;

    @Autowired
    private com.intelligence.platform.service.SourceIdentityService sourceIdentityService;

    @Autowired
    private com.intelligence.platform.service.ProjectContext projectContext;

    @Autowired
    private UploadTaskService uploadTaskService;

    @Autowired
    private VectorSearchService vectorSearchService;

    @Autowired
    private com.intelligence.platform.mapper.ProjectMapper projectMapper;

    @Autowired(required = false)
    private com.intelligence.platform.mapper.SettingMapper settingMapper;

    @Value("${upload.dir:./uploads}")
    private String uploadDir;

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

    /** docType → 库中文名映射 */
    private static final java.util.Map<String, String> DOC_TYPE_LABELS = java.util.Map.of(
            "report", "研究报告",
            "dynamic", "动态信息",
            "translation", "图书",
            "chart", "图表数据",
            "policy", "政策文件",
            "news", "新闻资讯"
    );

    /**
     * 生成自动命名：{项目名}_{库名}_{序号}
     * 图表类型特殊处理：{项目名}_{图片/表格}_{序号}.{扩展名}
     * 序号 = 该项目该库下已有文档数 + 1
     */
    private String generateAutoTitle(Long projectId, String docType, String fileType, String originalFilename) {
        String projectName = "未命名项目";
        if (projectId != null) {
            com.intelligence.platform.entity.Project project = projectMapper.selectById(projectId);
            if (project != null) {
                projectName = project.getName();
            }
        }

        String libraryLabel;
        if ("chart".equals(docType)) {
            // 图表类型根据 fileType 动态选择"图片"或"表格"
            libraryLabel = "table".equalsIgnoreCase(fileType) ? "表格" : "图片";
        } else {
            libraryLabel = DOC_TYPE_LABELS.getOrDefault(docType, docType);
        }

        // 计算该项目该库下的文档数量
        long count = documentMapper.selectCount(
                new LambdaQueryWrapper<Document>()
                        .eq(Document::getProjectId, projectId)
                        .eq(Document::getDocType, docType));
        long seq = count + 1;

        String title = projectName + "_" + libraryLabel + "_" + seq;

        // 图表类型追加文件扩展名
        if ("chart".equals(docType) && originalFilename != null) {
            String ext = getExtension(originalFilename);
            if (!ext.isEmpty()) {
                title = title + "." + ext;
            }
        }

        return title;
    }

    /** 兼容旧调用（无 fileType 和 originalFilename） */
    private String generateAutoTitle(Long projectId, String docType) {
        return generateAutoTitle(projectId, docType, "", null);
    }

    // 目标库列表
    private static final java.util.List<java.util.Map<String, String>> LIBRARIES = java.util.List.of(
            java.util.Map.of("value", "report", "label", "研究报告库"),
            java.util.Map.of("value", "dynamic", "label", "动态信息库"),
            java.util.Map.of("value", "translation", "label", "译丛译著库"),
            java.util.Map.of("value", "chart", "label", "图表数据库"),
            java.util.Map.of("value", "policy", "label", "政策文件库"),
            java.util.Map.of("value", "news", "label", "新闻资讯库")
    );

    private String sha256(byte[] content) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(digest.digest(content));
    }

    @PostMapping("/")
    public Map<String, Object> uploadFile(
            @RequestParam("file") MultipartFile file,
            @RequestParam(defaultValue = "") String categoryL1,
            @RequestParam(defaultValue = "") String categoryL2,
            @RequestParam(defaultValue = "report") String docType,
            @RequestParam(defaultValue = "") String sourceOrigin,
            @RequestParam(required = false) Long sourceDocId,
            @RequestParam(required = false) Integer sourcePage,
            @RequestParam(defaultValue = "") String fileType,
            @RequestParam(defaultValue = "false") boolean forceReprocess) throws Exception {

        // 文件校验
        FileValidationService.ValidationResult validation = fileValidationService.validate(file);
        if (!validation.valid()) {
            return Map.of("status", "error",
                    "message", "文件校验失败: " + validation.message(),
                    "fileSize", validation.fileSize());
        }

        Path uploadPath = Paths.get(getEffectiveUploadDir());
        Files.createDirectories(uploadPath);

        byte[] content = file.getBytes();
        String fileHash = sha256(content);

        // 检查是否已存在相同哈希（除非强制重新处理）
        List<Document> existing = documentMapper.selectList(
                new LambdaQueryWrapper<Document>().eq(Document::getFileHash, fileHash));
        if (!existing.isEmpty() && !forceReprocess) {
            return Map.of("status", "skipped",
                    "message", "文件内容未变更，已跳过: " + existing.get(0).getTitle(),
                    "existing_id", existing.get(0).getId());
        }
        
        // 如果强制重新处理且文档已存在，删除旧的知识条目（数据库+向量索引）
        if (!existing.isEmpty() && forceReprocess) {
            Long existingDocId = existing.get(0).getId();
            // 先从向量索引中移除
            List<KnowledgeEntry> oldEntries = knowledgeEntryMapper.selectList(
                    new LambdaQueryWrapper<KnowledgeEntry>().eq(KnowledgeEntry::getDocumentId, existingDocId));
            if (oldEntries != null) {
                for (KnowledgeEntry oldEntry : oldEntries) {
                    try {
                        vectorSearchService.removeEntry(oldEntry.getId());
                    } catch (Exception ignored) {}
                }
            }
            knowledgeEntryMapper.delete(
                    new LambdaQueryWrapper<KnowledgeEntry>().eq(KnowledgeEntry::getDocumentId, existingDocId));
            documentMapper.deleteById(existingDocId);
        }

        // 保存文件到 raw/sources/ 目录（参考 llm_wiki 来源管理）
        Path sourcesRoot = sourceIdentityService.getSourcesRoot();
        String sourcePath = sourceIdentityService.computeSourcePath(
                file.getOriginalFilename(), categoryL1, categoryL2);
        Path targetPath = sourcesRoot.resolve(sourcePath);
        Files.createDirectories(targetPath.getParent());
        Files.write(targetPath, content);

        // 计算来源标识（参考 llm_wiki source-identity.ts）
        String sourceIdentity = sourceIdentityService.sourceIdentityForPath(targetPath.toString());
        String folderContext = sourceIdentityService.folderContextForPath(targetPath.toString());

        // 入库
        Long projectId = projectContext.getCurrentProjectId();
        String autoTitle = generateAutoTitle(projectId, docType, fileType, file.getOriginalFilename());

        Document doc = new Document();
        doc.setTitle(autoTitle);
        doc.setCategoryL1(categoryL1);
        doc.setCategoryL2(categoryL2);
        doc.setDocType(docType);
        doc.setFilePath(targetPath.toString());
        doc.setFileHash(fileHash);
        doc.setStatus("parsed");
        doc.setSourceOrigin(sourceOrigin.isEmpty()
                ? (folderContext.isEmpty() ? file.getOriginalFilename() : file.getOriginalFilename() + " [" + folderContext + "]")
                : sourceOrigin);
        doc.setSourcePath(sourcePath);
        doc.setSourceIdentity(sourceIdentity);
        doc.setFolderContext(folderContext);
        doc.setProjectId(projectId);
        doc.setSourceDocId(sourceDocId);
        doc.setSourcePage(sourcePage);
        documentMapper.insert(doc);

        // 提交异步处理任务（不阻塞上传响应）
        String parseHint = "chart".equals(docType) && !fileType.isEmpty() ? fileType : docType;
        uploadTaskService.submit(doc.getId(), autoTitle, parseHint);

        return Map.of("status", "success",
                "id", doc.getId(),
                "filename", file.getOriginalFilename(),
                "title", autoTitle,
                "size", content.length,
                "sha256", fileHash.substring(0, 16) + "...",
                "message", "已提交处理");
    }

    @PostMapping("/check-hash")
    public Map<String, Object> checkHash(@RequestParam("file") MultipartFile file) throws Exception {
        byte[] content = file.getBytes();
        String fileHash = sha256(content);
        List<Document> existing = documentMapper.selectList(
                new LambdaQueryWrapper<Document>().eq(Document::getFileHash, fileHash));
        if (!existing.isEmpty()) {
            return Map.of("exists", true, "existing_id", existing.get(0).getId(),
                    "title", existing.get(0).getTitle());
        }
        return Map.of("exists", false);
    }

    /**
     * 获取支持的文件类型列表
     */
    @GetMapping("/file-types")
    public Map<String, Object> getFileTypes() {
        return Map.of(
                "extensions", FileValidationService.ALL_ALLOWED_EXTENSIONS,
                "accept", String.join(",", FileValidationService.ALL_ALLOWED_EXTENSIONS.stream().map(e -> "." + e).toList())
        );
    }

    /**
     * 获取目标库列表
     */
    @GetMapping("/libraries")
    public List<Map<String, String>> getLibraries() {
        return LIBRARIES;
    }

    /**
     * 查询单个上传任务状态
     */
    @GetMapping("/task/{docId}")
    public Map<String, Object> getTaskStatus(@PathVariable Long docId) {
        return uploadTaskService.getStatus(docId);
    }

    /**
     * 查询所有活跃上传任务
     */
    @GetMapping("/tasks")
    public List<Map<String, Object>> getAllTasks() {
        return uploadTaskService.getAllTasks();
    }

    /**
     * 取消上传任务
     */
    @PostMapping("/task/{docId}/cancel")
    public Map<String, Object> cancelTask(@PathVariable Long docId) {
        uploadTaskService.cancel(docId);
        return Map.of("status", "success", "message", "任务已取消");
    }

    /**
     * 从URL上传文档（用于Blog/网页等动态信息）
     * 抓取网页内容，保存URL并解析
     */
    @PostMapping("/from-url")
    public Map<String, Object> uploadFromUrl(
            @RequestParam("url") String url,
            @RequestParam(defaultValue = "") String title,
            @RequestParam(defaultValue = "") String categoryL1,
            @RequestParam(defaultValue = "") String categoryL2,
            @RequestParam(defaultValue = "dynamic") String docType,
            @RequestParam(defaultValue = "") String sourceOrigin) throws Exception {

        // 验证URL格式
        if (url == null || url.isEmpty() || (!url.startsWith("http://") && !url.startsWith("https://"))) {
            return Map.of("status", "error", "message", "请提供有效的HTTP/HTTPS URL");
        }

        // 检查URL是否已存在
        List<Document> existing = documentMapper.selectList(
                new LambdaQueryWrapper<Document>().eq(Document::getUrl, url));
        if (!existing.isEmpty()) {
            return Map.of("status", "skipped",
                    "message", "URL已存在，已跳过: " + existing.get(0).getTitle(),
                    "existing_id", existing.get(0).getId());
        }

        // 抓取网页内容
        String content;
        String fetchedTitle;
        try {
            java.net.URL urlObj = new java.net.URL(url);
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) urlObj.openConnection();
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
            conn.setConnectTimeout(30000);
            conn.setReadTimeout(30000);

            byte[] bytes = conn.getInputStream().readAllBytes();
            content = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);

            // 提取标题
            fetchedTitle = extractTitle(content);
            if (title == null || title.isEmpty()) {
                title = fetchedTitle.isEmpty() ? url : fetchedTitle;
            }
        } catch (Exception e) {
            return Map.of("status", "error", "message", "抓取URL失败: " + e.getMessage());
        }

        // 生成内容哈希
        String fileHash = sha256(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));

        // 保存HTML内容到文件
        Path sourcesRoot = sourceIdentityService.getSourcesRoot();
        String sourcePath = sourceIdentityService.computeSourcePath(
                title.replaceAll("[^a-zA-Z0-9\\u4e00-\\u9fa5]", "_") + ".html", categoryL1, categoryL2);
        Path targetPath = sourcesRoot.resolve(sourcePath);
        Files.createDirectories(targetPath.getParent());
        Files.writeString(targetPath, content);

        String sourceIdentity = sourceIdentityService.sourceIdentityForPath(targetPath.toString());
        String folderContext = sourceIdentityService.folderContextForPath(targetPath.toString());

        // 入库
        Document doc = new Document();
        doc.setTitle(title);
        doc.setCategoryL1(categoryL1);
        doc.setCategoryL2(categoryL2);
        doc.setDocType(docType);
        doc.setFilePath(targetPath.toString());
        doc.setFileHash(fileHash);
        doc.setStatus("parsed");
        doc.setSourceOrigin(sourceOrigin.isEmpty() ? url : sourceOrigin);
        doc.setSourcePath(sourcePath);
        doc.setSourceIdentity(sourceIdentity);
        doc.setFolderContext(folderContext);
        doc.setProjectId(projectContext.getCurrentProjectId());
        doc.setUrl(url);
        documentMapper.insert(doc);

        // LLM抽取知识词条
        int entryCount = 0;
        try {
            List<KnowledgeEntry> entries = documentParseService.parseAndExtract(doc.getId());
            entryCount = entries.size();
        } catch (Exception e) {
            entryCount = -1;
        }

        return Map.of("status", "success",
                "id", doc.getId(),
                "title", title,
                "url", url,
                "entry_count", entryCount,
                "message", entryCount > 0
                        ? "URL内容已抓取，LLM已抽取 " + entryCount + " 个知识词条"
                        : "URL内容已抓取（LLM抽取未完成，请稍后查看词条）");
    }

    /**
     * 从HTML内容中提取标题
     */
    private String extractTitle(String html) {
        // 尝试从<title>标签提取
        int start = html.indexOf("<title");
        if (start >= 0) {
            start = html.indexOf(">", start);
            int end = html.indexOf("</title>", start);
            if (start >= 0 && end > start) {
                return html.substring(start + 1, end).trim();
            }
        }
        // 尝试从<h1>标签提取
        start = html.indexOf("<h1");
        if (start >= 0) {
            start = html.indexOf(">", start);
            int end = html.indexOf("</h1>", start);
            if (start >= 0 && end > start) {
                return html.substring(start + 1, end).trim().replaceAll("<[^>]+>", "");
            }
        }
        return "";
    }

    /**
     * AI智能分析文档分类（根据文件内容推荐分类和目标库）
     */
    @PostMapping("/analyze")
    public Map<String, String> analyzeDocument(@RequestParam("file") MultipartFile file) {
        try {
            String filename = file.getOriginalFilename();
            String extension = getExtension(filename);

            // 检查文件类型
            if (!FileValidationService.ALL_ALLOWED_EXTENSIONS.contains(extension)) {
                return Map.of("error", "不支持的文件类型: " + extension);
            }

            // 读取文件内容（简化版，只读取前2000字符）
            String content = "";
            if (extension.equals("txt") || extension.equals("md") || extension.equals("markdown") || extension.equals("csv")) {
                byte[] bytes = file.getBytes();
                content = new String(bytes, 0, Math.min(bytes.length, 2000));
            } else {
                // 其他文件类型需要专门的解析库
                content = "文件名: " + filename + "，类型: " + extension;
            }

            // 调用AI分析
            Map<String, String> classification = documentParseService.analyzeAndClassify(filename, content);
            return classification;

        } catch (Exception e) {
            return Map.of("error", "分析失败: " + e.getMessage());
        }
    }

    /**
     * 批量上传文件（支持选择目标库）
     */
    @PostMapping("/batch")
    public Map<String, Object> batchUpload(
            @RequestParam("files") List<MultipartFile> files,
            @RequestParam(defaultValue = "") String categoryL1,
            @RequestParam(defaultValue = "") String categoryL2,
            @RequestParam(defaultValue = "report") String docType,
            @RequestParam(defaultValue = "report") String library,
            @RequestParam(defaultValue = "false") boolean autoClassify,
            @RequestParam(defaultValue = "") String sourceOrigin) throws Exception {

        Path uploadPath = Paths.get(getEffectiveUploadDir());
        Files.createDirectories(uploadPath);

        List<Map<String, Object>> results = new java.util.ArrayList<>();

        for (MultipartFile file : files) {
            String filename = file.getOriginalFilename();
            String extension = getExtension(filename);

            // 完整文件校验
            FileValidationService.ValidationResult validation = fileValidationService.validate(file);
            if (!validation.valid()) {
                results.add(Map.of(
                        "filename", filename != null ? filename : "unknown",
                        "status", "error",
                        "message", "文件校验失败: " + validation.message()
                ));
                continue;
            }

            byte[] content = file.getBytes();
            String fileHash = sha256(content);

            // 检查是否已存在
            List<Document> existing = documentMapper.selectList(
                    new LambdaQueryWrapper<Document>().eq(Document::getFileHash, fileHash));
            if (!existing.isEmpty()) {
                results.add(Map.of(
                        "filename", filename,
                        "status", "skipped",
                        "message", "文件内容未变更，已跳过"
                ));
                continue;
            }

            // AI智能分类
            String finalCategoryL1 = categoryL1;
            String finalDocType = docType;
            String finalLibrary = library;

            if (autoClassify) {
                try {
                    String textContent = "";
                    if (extension.equals("txt") || extension.equals("md") || extension.equals("markdown") || extension.equals("csv")) {
                        textContent = new String(content, 0, Math.min(content.length, 2000));
                    } else {
                        textContent = "文件名: " + filename;
                    }
                    Map<String, String> classification = documentParseService.analyzeAndClassify(filename, textContent);
                    finalCategoryL1 = classification.getOrDefault("categoryL1", categoryL1);
                    finalDocType = classification.getOrDefault("docType", docType);
                    finalLibrary = classification.getOrDefault("library", library);
                } catch (Exception e) {
                    // AI分类失败，使用默认值
                }
            }

            // 保存文件到 raw/sources/ 目录（参考 llm_wiki 来源管理）
            Path sourcesRoot = sourceIdentityService.getSourcesRoot();
            String sourcePath = sourceIdentityService.computeSourcePath(filename, finalCategoryL1, categoryL2);
            Path targetPath = sourcesRoot.resolve(sourcePath);
            Files.createDirectories(targetPath.getParent());
            Files.write(targetPath, content);

            // 计算来源标识
            String sourceIdentity = sourceIdentityService.sourceIdentityForPath(targetPath.toString());
            String folderContext = sourceIdentityService.folderContextForPath(targetPath.toString());

            // 入库
            Long projectId = projectContext.getCurrentProjectId();

            // 检测文件类型（用于图表库自动区分图片/表格）
            String fileType = "";
            if ("chart".equals(finalDocType)) {
                String ext = extension.toLowerCase();
                if (FileValidationService.IMAGE_EXTENSIONS.contains(ext)) {
                    fileType = "image";
                } else if (FileValidationService.TABLE_EXTENSIONS.contains(ext)) {
                    fileType = "table";
                }
            }

            // 生成标题（传递 fileType 和 originalFilename，确保图表类文件保留扩展名）
            String autoTitle = generateAutoTitle(projectId, finalDocType, fileType, filename);
            Document doc = new Document();
            doc.setTitle(autoTitle);
            doc.setCategoryL1(finalCategoryL1);
            doc.setCategoryL2(categoryL2);
            doc.setDocType(finalDocType);
            doc.setFilePath(targetPath.toString());
            doc.setFileHash(fileHash);
            doc.setStatus("parsed");
            doc.setSourceOrigin(sourceOrigin.isEmpty()
                    ? (folderContext.isEmpty() ? filename : filename + " [" + folderContext + "]")
                    : sourceOrigin);
            doc.setSourcePath(sourcePath);
            doc.setSourceIdentity(sourceIdentity);
            doc.setFolderContext(folderContext);
            doc.setProjectId(projectId);
            documentMapper.insert(doc);

            // 解析文档并提取知识词条
            int entryCount = 0;
            String errorMsg = null;
            try {
                List<com.intelligence.platform.entity.KnowledgeEntry> entries = documentParseService.parseAndExtract(doc.getId(), finalLibrary);
                entryCount = entries.size();
            } catch (Exception e) {
                // 解析失败，记录错误并返回详细信息
                errorMsg = e.getMessage();
                if (errorMsg == null || errorMsg.length() > 200) {
                    errorMsg = (errorMsg != null ? errorMsg.substring(0, 200) : "未知错误");
                }
                System.err.println("解析文档失败 (id=" + doc.getId() + ", title=" + autoTitle + "): " + errorMsg);
                entryCount = -1;
            }

            Map<String, Object> resultItem = new java.util.LinkedHashMap<>();
            resultItem.put("filename", filename);
            resultItem.put("status", entryCount >= 0 ? "success" : "error");
            resultItem.put("id", doc.getId());
            resultItem.put("library", finalLibrary);
            resultItem.put("category", finalCategoryL1);
            resultItem.put("entry_count", entryCount);
            if (errorMsg != null) {
                resultItem.put("message", "词条抽取失败: " + errorMsg);
            }
            results.add(resultItem);
        }

        return Map.of("total", files.size(), "results", results);
    }

    /**
     * 获取文件扩展名
     */
    private String getExtension(String filename) {
        if (filename == null) return "";
        int lastDot = filename.lastIndexOf('.');
        return lastDot > 0 ? filename.substring(lastDot + 1).toLowerCase() : "";
    }

    /**
     * 手动重新处理卡在 parsed 状态的文档
     */
    @PostMapping("/recover-stuck")
    public Map<String, Object> recoverStuckDocuments() {
        try {
            List<Document> stuckDocs = documentMapper.selectList(
                    new LambdaQueryWrapper<Document>()
                            .eq(Document::getStatus, "parsed")
            );
            
            if (stuckDocs.isEmpty()) {
                return Map.of("success", true, "message", "没有需要恢复的文档", "count", 0);
            }
            
            int count = 0;
            for (Document doc : stuckDocs) {
                uploadTaskService.submit(doc.getId(), doc.getTitle(), doc.getDocType());
                count++;
            }
            
            return Map.of("success", true, "message", "已重新提交 " + count + " 个文档", "count", count);
        } catch (Exception e) {
            e.printStackTrace();
            return Map.of("success", false, "message", "恢复失败: " + e.getMessage());
        }
    }
}
