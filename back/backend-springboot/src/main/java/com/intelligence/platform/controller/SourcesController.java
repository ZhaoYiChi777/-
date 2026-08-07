package com.intelligence.platform.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.intelligence.platform.entity.Document;
import com.intelligence.platform.entity.KnowledgeEntry;
import com.intelligence.platform.mapper.DocumentMapper;
import com.intelligence.platform.mapper.KnowledgeEntryMapper;
import com.intelligence.platform.service.DocumentParseService;
import com.intelligence.platform.service.FileValidationService;
import com.intelligence.platform.service.SourceIdentityService;
import com.intelligence.platform.service.VectorSearchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.stream.Stream;

/**
 * 来源管理控制器（参考 llm_wiki 的 sources-view.tsx + source-lifecycle.ts）
 * 提供来源文件树、文件夹导入、来源删除等功能
 *
 * 来源文件统一存放于 {uploadDir}/raw/sources/ 目录下，
 * 目录结构即分类（如 raw/sources/论文/2024/transformer.pdf）
 */
@RestController
@RequestMapping("/api/sources")
@CrossOrigin(origins = "*")
public class SourcesController {

    private static final Logger log = LoggerFactory.getLogger(SourcesController.class);

    @Autowired
    private DocumentMapper documentMapper;
    @Autowired
    private KnowledgeEntryMapper knowledgeEntryMapper;
    @Autowired
    private DocumentParseService documentParseService;
    @Autowired
    private FileValidationService fileValidationService;
    @Autowired
    private SourceIdentityService sourceIdentityService;
    @Autowired
    private VectorSearchService vectorSearchService;

    @Value("${upload.dir:./uploads}")
    private String uploadDir;

    /**
     * 获取实际生效的上传目录
     */
    private String getEffectiveUploadDir() {
        return sourceIdentityService.getEffectiveUploadDir();
    }

    /**
     * 获取来源目录树（参考 llm_wiki listDirectory）
     * 扫描 raw/sources/ 目录，返回树形结构
     */
    @GetMapping("/tree")
    public Map<String, Object> getSourceTree() {
        Path sourcesRoot = sourceIdentityService.getSourcesRoot();
        List<Map<String, Object>> tree = new ArrayList<>();
        try {
            if (Files.exists(sourcesRoot)) {
                tree = buildTree(sourcesRoot, sourcesRoot);
            }
        } catch (IOException e) {
            log.warn("读取来源目录失败: {}", e.getMessage());
        }
        // 同时返回数据库中的来源文件统计
        long totalDocs = documentMapper.selectCount(null);
        long sourcesCount = documentMapper.selectCount(
                new LambdaQueryWrapper<Document>().isNotNull(Document::getSourcePath));

        return Map.of(
                "tree", tree,
                "root", sourcesRoot.toString(),
                "total_documents", totalDocs,
                "sources_count", sourcesCount
        );
    }

    /**
     * 导入文件夹中的所有文件（参考 llm_wiki importSourceFolder）
     * 将文件夹递归复制到 raw/sources/ 下，并逐个入库
     */
    @PostMapping("/import-folder")
    public Map<String, Object> importFolder(
            @RequestParam("folderPath") String folderPath,
            @RequestParam(defaultValue = "") String categoryL1,
            @RequestParam(defaultValue = "") String categoryL2,
            @RequestParam(defaultValue = "") String sourceOrigin) throws Exception {

        Path sourceFolder = Path.of(folderPath);
        if (!Files.isDirectory(sourceFolder)) {
            return Map.of("status", "error", "message", "路径不是有效目录: " + folderPath);
        }

        Path sourcesRoot = sourceIdentityService.getSourcesRoot();
        String folderName = sourceFolder.getFileName().toString();
        Path targetDir = sourcesRoot.resolve(folderName);
        Files.createDirectories(targetDir);

        List<Map<String, Object>> results = new ArrayList<>();
        int imported = 0;
        int skipped = 0;

        try (Stream<Path> walk = Files.walk(sourceFolder)) {
            List<Path> files = walk.filter(Files::isRegularFile).toList();
            for (Path file : files) {
                String fileName = file.getFileName().toString();
                String extension = getExtension(fileName);

                if (!FileValidationService.ALL_ALLOWED_EXTENSIONS.contains(extension)) {
                    results.add(Map.of("filename", fileName, "status", "skipped", "message", "不支持的文件类型"));
                    skipped++;
                    continue;
                }

                // 计算目标路径（保持子目录结构）
                Path relativePath = sourceFolder.relativize(file);
                Path targetPath = targetDir.resolve(relativePath);
                Files.createDirectories(targetPath.getParent());

                byte[] content = Files.readAllBytes(file);
                String fileHash = sha256(content);

                // 检查去重
                List<Document> existing = documentMapper.selectList(
                        new LambdaQueryWrapper<Document>().eq(Document::getFileHash, fileHash));
                if (!existing.isEmpty()) {
                    results.add(Map.of("filename", fileName, "status", "skipped", "message", "文件内容未变更"));
                    skipped++;
                    continue;
                }

                // 复制文件到 raw/sources/
                Files.write(targetPath, content);

                // 计算来源标识
                String sourcePath = folderName + "/" + relativePath.toString().replace('\\', '/');
                String sourceIdentity = sourceIdentityService.sourceIdentityForPath(targetPath.toString());
                String folderContext = sourceIdentityService.folderContextForPath(targetPath.toString());

                // 入库
                Document doc = new Document();
                doc.setTitle(fileName);
                doc.setCategoryL1(categoryL1);
                doc.setCategoryL2(categoryL2);
                doc.setDocType("report");
                doc.setFilePath(targetPath.toString());
                doc.setFileHash(fileHash);
                doc.setStatus("parsed");
                doc.setSourceOrigin(sourceOrigin.isEmpty() ? buildDefaultOrigin(fileName, folderContext) : sourceOrigin);
                doc.setSourcePath(sourcePath);
                doc.setSourceIdentity(sourceIdentity);
                doc.setFolderContext(folderContext);
                documentMapper.insert(doc);

                // 异步抽取
                int entryCount = 0;
                try {
                    var entries = documentParseService.parseAndExtract(doc.getId());
                    entryCount = entries.size();
                } catch (Exception e) {
                    entryCount = -1;
                }

                results.add(Map.of(
                        "filename", fileName,
                        "status", "success",
                        "id", doc.getId(),
                        "source_path", sourcePath,
                        "folder_context", folderContext,
                        "entry_count", entryCount
                ));
                imported++;
            }
        }

        return Map.of(
                "status", "success",
                "imported", imported,
                "skipped", skipped,
                "total", results.size(),
                "results", results
        );
    }

    /**
     * 导入单个文件到 raw/sources/ 下（参考 llm_wiki importSourceFiles）
     */
    @PostMapping("/import-file")
    public Map<String, Object> importFile(
            @RequestParam("file") MultipartFile file,
            @RequestParam(defaultValue = "") String subFolder,
            @RequestParam(defaultValue = "") String categoryL1,
            @RequestParam(defaultValue = "") String categoryL2,
            @RequestParam(defaultValue = "") String sourceOrigin) throws Exception {

        Path sourcesRoot = sourceIdentityService.getSourcesRoot();
        Path targetDir = subFolder.isEmpty() ? sourcesRoot : sourcesRoot.resolve(subFolder);
        Files.createDirectories(targetDir);

        String fileName = file.getOriginalFilename();
        Path targetPath = targetDir.resolve(fileName);

        byte[] content = file.getBytes();
        String fileHash = sha256(content);

        // 去重
        List<Document> existing = documentMapper.selectList(
                new LambdaQueryWrapper<Document>().eq(Document::getFileHash, fileHash));
        if (!existing.isEmpty()) {
            return Map.of("status", "skipped", "message", "文件内容未变更，已跳过");
        }

        Files.write(targetPath, content);

        String sourcePath = (subFolder.isEmpty() ? "" : subFolder + "/") + fileName;
        String sourceIdentity = sourceIdentityService.sourceIdentityForPath(targetPath.toString());
        String folderContext = sourceIdentityService.folderContextForPath(targetPath.toString());

        Document doc = new Document();
        doc.setTitle(fileName);
        doc.setCategoryL1(categoryL1);
        doc.setCategoryL2(categoryL2);
        doc.setDocType("report");
        doc.setFilePath(targetPath.toString());
        doc.setFileHash(fileHash);
        doc.setStatus("parsed");
        doc.setSourceOrigin(sourceOrigin.isEmpty() ? buildDefaultOrigin(fileName, folderContext) : sourceOrigin);
        doc.setSourcePath(sourcePath);
        doc.setSourceIdentity(sourceIdentity);
        doc.setFolderContext(folderContext);
        documentMapper.insert(doc);

        int entryCount = 0;
        try {
            var entries = documentParseService.parseAndExtract(doc.getId());
            entryCount = entries.size();
        } catch (Exception e) {
            entryCount = -1;
        }

        return Map.of(
                "status", "success",
                "id", doc.getId(),
                "source_path", sourcePath,
                "source_identity", sourceIdentity,
                "folder_context", folderContext,
                "entry_count", entryCount
        );
    }

    /**
     * 删除来源文件（参考 llm_wiki deleteSourceFile）
     * 同时删除磁盘文件和数据库记录
     */
    @DeleteMapping("/{id}")
    public Map<String, Object> deleteSource(@PathVariable Long id) {
        Document doc = documentMapper.selectById(id);
        if (doc == null) {
            return Map.of("status", "error", "message", "文档不存在");
        }

        // 1. 删除关联的知识词条（数据库）
        LambdaQueryWrapper<KnowledgeEntry> deleteWrapper = new LambdaQueryWrapper<>();
        deleteWrapper.eq(KnowledgeEntry::getDocumentId, id);
        List<KnowledgeEntry> entriesToDelete = knowledgeEntryMapper.selectList(deleteWrapper);
        int deletedEntries = 0;
        try {
            deletedEntries = knowledgeEntryMapper.delete(deleteWrapper);
            // 2. 从向量索引中移除
            if (entriesToDelete != null) {
                for (KnowledgeEntry entry : entriesToDelete) {
                    try {
                        vectorSearchService.removeEntry(entry.getId());
                    } catch (Exception ignored) {
                        log.warn("从向量索引移除词条失败: {}", entry.getId());
                    }
                }
            }
        } catch (Exception e) {
            log.warn("清理关联词条失败: {}", e.getMessage());
        }

        // 3. 删除磁盘文件
        if (doc.getFilePath() != null) {
            try {
                Files.deleteIfExists(Path.of(doc.getFilePath()));
            } catch (IOException e) {
                log.warn("删除来源文件失败: {}", e.getMessage());
            }
        }

        documentMapper.deleteById(id);
        return Map.of("status", "success", "message", "来源已删除: " + doc.getTitle(),
                "deleted_entries", deletedEntries);
    }

    /**
     * 刷新来源目录（重新扫描 raw/sources/）
     */
    @PostMapping("/refresh")
    public Map<String, Object> refreshSources() {
        Path sourcesRoot = sourceIdentityService.getSourcesRoot();
        int found = 0;
        int newDocs = 0;

        try {
            if (!Files.exists(sourcesRoot)) {
                return Map.of("status", "success", "found", 0, "new", 0, "message", "来源目录为空");
            }

            try (Stream<Path> walk = Files.walk(sourcesRoot)) {
                List<Path> files = walk.filter(Files::isRegularFile).toList();
                for (Path file : files) {
                    String fileName = file.getFileName().toString();
                    if (fileName.startsWith(".")) continue;

                    found++;
                    // 检查是否已入库
                    String filePath = file.toString();
                    List<Document> existing = documentMapper.selectList(
                            new LambdaQueryWrapper<Document>().eq(Document::getFilePath, filePath));
                    if (!existing.isEmpty()) continue;

                    // 新文件入库
                    byte[] content = Files.readAllBytes(file);
                    String fileHash = sha256(content);
                    String sourceIdentity = sourceIdentityService.sourceIdentityForPath(filePath);
                    String folderContext = sourceIdentityService.folderContextForPath(filePath);
                    String sourcePath = sourceIdentity; // identity 就是相对于 raw/sources/ 的路径

                    Document doc = new Document();
                    doc.setTitle(fileName);
                    doc.setDocType("report");
                    doc.setFilePath(filePath);
                    doc.setFileHash(fileHash);
                    doc.setStatus("parsed");
                    doc.setSourcePath(sourcePath);
                    doc.setSourceIdentity(sourceIdentity);
                    doc.setFolderContext(folderContext);
                    doc.setSourceOrigin(buildDefaultOrigin(fileName, folderContext));
                    documentMapper.insert(doc);
                    newDocs++;
                }
            }
        } catch (Exception e) {
            return Map.of("status", "error", "message", "刷新失败: " + e.getMessage());
        }

        return Map.of("status", "success", "found", found, "new", newDocs,
                "message", String.format("扫描完成：发现 %d 个文件，新增 %d 个", found, newDocs));
    }

    // === 辅助方法 ===

    private List<Map<String, Object>> buildTree(Path dir, Path root) throws IOException {
        List<Map<String, Object>> nodes = new ArrayList<>();
        try (var stream = Files.list(dir)) {
            List<Path> entries = stream.sorted().toList();
            for (Path entry : entries) {
                String name = entry.getFileName().toString();
                if (name.startsWith(".")) continue;

                Map<String, Object> node = new LinkedHashMap<>();
                node.put("name", name);
                node.put("path", root.relativize(entry).toString().replace('\\', '/'));
                node.put("is_dir", Files.isDirectory(entry));

                if (Files.isDirectory(entry)) {
                    node.put("children", buildTree(entry, root));
                } else {
                    node.put("size", Files.size(entry));
                    // 检查是否已入库
                    List<Document> docs = documentMapper.selectList(
                            new LambdaQueryWrapper<Document>().eq(Document::getFilePath, entry.toString()));
                    node.put("imported", !docs.isEmpty());
                    if (!docs.isEmpty()) {
                        node.put("doc_id", docs.get(0).getId());
                    }
                }
                nodes.add(node);
            }
        }
        return nodes;
    }

    private String buildDefaultOrigin(String fileName, String folderContext) {
        if (folderContext != null && !folderContext.isEmpty()) {
            return fileName + " [" + folderContext + "]";
        }
        return fileName;
    }

    private String sha256(byte[] content) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return java.util.HexFormat.of().formatHex(digest.digest(content));
    }

    private String getExtension(String filename) {
        if (filename == null) return "";
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(dot + 1).toLowerCase() : "";
    }
}
