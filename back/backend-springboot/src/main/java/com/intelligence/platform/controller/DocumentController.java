package com.intelligence.platform.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.intelligence.platform.common.PageResult;
import com.intelligence.platform.entity.Document;
import com.intelligence.platform.mapper.DocumentMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/documents")
@CrossOrigin(origins = "*")
public class DocumentController {

    private static final Logger log = LoggerFactory.getLogger(DocumentController.class);

    @Autowired
    private DocumentMapper documentMapper;

    @Autowired
    private com.intelligence.platform.mapper.KnowledgeEntryMapper knowledgeEntryMapper;

    @Autowired
    private com.intelligence.platform.service.DocumentParseService documentParseService;

    @Autowired
    private com.intelligence.platform.service.VectorSearchService vectorSearchService;

    @Autowired
    private com.intelligence.platform.service.ProjectContext projectContext;

    @Autowired
    private com.intelligence.platform.mapper.ProjectMapper projectMapper;

    @GetMapping
    public PageResult<Document> listDocuments(
            @RequestParam(required = false) String docType,
            @RequestParam(required = false) String categoryL1,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {

        LambdaQueryWrapper<Document> wrapper = new LambdaQueryWrapper<>();
        // 项目隔离
        Long projectId = projectContext.getCurrentProjectId();
        if (projectId != null) wrapper.eq(Document::getProjectId, projectId);
        if (docType != null && !docType.isEmpty()) wrapper.eq(Document::getDocType, docType);
        if (categoryL1 != null && !categoryL1.isEmpty()) wrapper.eq(Document::getCategoryL1, categoryL1);
        if (status != null && !status.isEmpty()) wrapper.eq(Document::getStatus, status);
        if (keyword != null && !keyword.isEmpty()) {
            wrapper.and(w -> w.like(Document::getTitle, keyword).or().like(Document::getKeywords, keyword));
        }
        wrapper.orderByDesc(Document::getUploadTime);

        Page<Document> pageObj = new Page<>(page, pageSize);
        Page<Document> result = documentMapper.selectPage(pageObj, wrapper);
        return new PageResult<>(result.getTotal(), page, pageSize, result.getRecords());
    }

    @GetMapping("/{id}")
    public Document getDocument(@PathVariable Long id) {
        return documentMapper.selectById(id);
    }

    @PostMapping("/")
    public Map<String, Object> createDocument(@RequestBody Document doc) {
        documentMapper.insert(doc);
        return Map.of("id", doc.getId(), "message", "创建成功");
    }

    @PutMapping("/{id}")
    public Map<String, Object> updateDocument(@PathVariable Long id, @RequestBody Document doc) {
        doc.setId(id);
        documentMapper.updateById(doc);
        return Map.of("message", "更新成功");
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> deleteDocument(@PathVariable Long id) {
        Document doc = documentMapper.selectById(id);
        if (doc == null) {
            return Map.of("message", "文档不存在");
        }

        // 1. 删除关联的知识词条（数据库）
        com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.intelligence.platform.entity.KnowledgeEntry> deleteWrapper =
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<>();
        deleteWrapper.eq(com.intelligence.platform.entity.KnowledgeEntry::getDocumentId, id);
        java.util.List<com.intelligence.platform.entity.KnowledgeEntry> entriesToDelete = knowledgeEntryMapper.selectList(deleteWrapper);
        int deletedEntries = 0;
        try {
            deletedEntries = knowledgeEntryMapper.delete(deleteWrapper);
            // 2. 从向量索引中移除
            if (entriesToDelete != null) {
                for (com.intelligence.platform.entity.KnowledgeEntry entry : entriesToDelete) {
                    try {
                        vectorSearchService.removeEntry(entry.getId());
                    } catch (Exception ignored) {}
                }
            }
            // 3. 删除物理文件
            if (doc.getFilePath() != null) {
                try {
                    java.nio.file.Files.deleteIfExists(java.nio.file.Path.of(doc.getFilePath()));
                } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            log.warn("清理关联词条失败: {}", e.getMessage());
        }

        // 4. 删除文档记录
        documentMapper.deleteById(id);

        return Map.of(
                "message", "删除成功",
                "deleted_entries", deletedEntries
        );
    }

    /**
     * 重新抽取文档的知识词条（删除旧词条后重新调用LLM抽取）
     */
    @PostMapping("/{id}/re-extract")
    public Map<String, Object> reExtract(@PathVariable Long id,
                                          @RequestParam(required = false) String fileType) {
        Document doc = documentMapper.selectById(id);
        if (doc == null) {
            return Map.of("status", "error", "message", "文档不存在");
        }

        // 删除旧的失败词条
        com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.intelligence.platform.entity.KnowledgeEntry> deleteWrapper =
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<>();
        deleteWrapper.eq(com.intelligence.platform.entity.KnowledgeEntry::getDocumentId, id);
        int deleted = 0;
        try {
            deleted = knowledgeEntryMapper.delete(deleteWrapper);
        } catch (Exception e) {
            // table may not exist yet
        }

        // 重新抽取，支持指定 fileType（例如对图片表格指定 table 走 VLM 识别）
        try {
            java.util.List<com.intelligence.platform.entity.KnowledgeEntry> entries =
                    documentParseService.parseAndExtract(id, fileType);
            return Map.of(
                    "status", "success",
                    "message", "重新抽取完成",
                    "entry_count", entries.size(),
                    "deleted_old", deleted
            );
        } catch (Exception e) {
            return Map.of("status", "error", "message", "抽取失败: " + e.getMessage());
        }
    }

    /**
     * 修复历史文档的 project_id（数据迁移：回填 NULL project_id）
     * 策略：1) 通过标题前缀匹配项目名称；2) 单项目兜底；3) 同步更新关联 knowledge_entries
     */
    @PostMapping("/repair-project-id")
    public Map<String, Object> repairProjectId(@RequestParam(required = false) Long fallbackProjectId) {
        LambdaQueryWrapper<Document> wrapper = new LambdaQueryWrapper<>();
        wrapper.isNull(Document::getProjectId);
        List<Document> orphanDocs = documentMapper.selectList(wrapper);

        int totalScanned = orphanDocs.size();
        int totalRepaired = 0;
        int totalOrphan = 0;

        List<com.intelligence.platform.entity.Project> allProjects = projectMapper.selectList(null);
        if (fallbackProjectId == null && allProjects.size() == 1) {
            fallbackProjectId = allProjects.get(0).getId();
        }

        for (Document doc : orphanDocs) {
            Long targetProjectId = null;

            // 1. 标题前缀匹配
            if (doc.getTitle() != null) {
                for (com.intelligence.platform.entity.Project p : allProjects) {
                    if (doc.getTitle().startsWith(p.getName() + "_")) {
                        targetProjectId = p.getId();
                        break;
                    }
                }
            }

            // 2. 单项目兜底
            if (targetProjectId == null) {
                targetProjectId = fallbackProjectId;
            }

            if (targetProjectId != null) {
                doc.setProjectId(targetProjectId);
                documentMapper.updateById(doc);
                totalRepaired++;
                log.info("Repair document project_id: '{}' (id={}) -> projectId={}",
                        doc.getTitle(), doc.getId(), targetProjectId);

                // 同步更新该 document 关联的所有 knowledge_entries
                LambdaQueryWrapper<com.intelligence.platform.entity.KnowledgeEntry> entryWrapper =
                        new LambdaQueryWrapper<>();
                entryWrapper.eq(com.intelligence.platform.entity.KnowledgeEntry::getDocumentId, doc.getId());
                List<com.intelligence.platform.entity.KnowledgeEntry> entries = knowledgeEntryMapper.selectList(entryWrapper);
                for (com.intelligence.platform.entity.KnowledgeEntry entry : entries) {
                    if (entry.getProjectId() == null) {
                        entry.setProjectId(targetProjectId);
                        knowledgeEntryMapper.updateById(entry);
                        log.info("Repair entry project_id via document: entry '{}' (id={}) -> projectId={}",
                                entry.getTitle(), entry.getId(), targetProjectId);
                    }
                }
            } else {
                totalOrphan++;
                log.warn("Repair document project_id: '{}' (id={}) 无法确定归属项目，保留 NULL",
                        doc.getTitle(), doc.getId());
            }
        }

        return Map.of(
                "message", "文档 project_id 修复完成",
                "total_scanned", totalScanned,
                "total_repaired", totalRepaired,
                "total_orphan", totalOrphan
        );
    }

    /**
     * 批量重命名图表文档：将已有 docType=chart 的文档标题更新为新格式
     * 新格式：{项目名}_{图片/表格}_{序号}.{扩展名}
     */
    @PatchMapping("/batch-rename-charts")
    public Map<String, Object> batchRenameCharts() {
        Long projectId = projectContext.getCurrentProjectId();

        // 查询当前项目下所有 docType=chart 的文档
        LambdaQueryWrapper<Document> wrapper = new LambdaQueryWrapper<>();
        if (projectId != null) wrapper.eq(Document::getProjectId, projectId);
        wrapper.eq(Document::getDocType, "chart");
        wrapper.orderByAsc(Document::getUploadTime);
        List<Document> charts = documentMapper.selectList(wrapper);

        // 获取项目名
        String projectName = "未命名项目";
        if (projectId != null) {
            com.intelligence.platform.entity.Project project = projectMapper.selectById(projectId);
            if (project != null) {
                projectName = project.getName();
            }
        }

        int updatedCount = 0;
        for (int i = 0; i < charts.size(); i++) {
            Document doc = charts.get(i);
            long seq = i + 1;

            // 根据 categoryL1 判断"图片"或"表格"
            String label = "图片"; // 默认
            if (doc.getCategoryL1() != null) {
                String cat = doc.getCategoryL1().toLowerCase();
                if (cat.contains("表") || cat.equals("table")) {
                    label = "表格";
                }
            }

            // 从 filePath 或 sourcePath 提取扩展名
            String ext = "";
            String path = doc.getFilePath() != null ? doc.getFilePath() : doc.getSourcePath();
            if (path != null) {
                int lastDot = path.lastIndexOf('.');
                if (lastDot > 0) {
                    ext = path.substring(lastDot + 1).toLowerCase();
                }
            }

            String newTitle = projectName + "_" + label + "_" + seq;
            if (!ext.isEmpty()) {
                newTitle = newTitle + "." + ext;
            }

            doc.setTitle(newTitle);
            documentMapper.updateById(doc);
            updatedCount++;

            // 同步更新关联 knowledge_entries 的 title
            LambdaQueryWrapper<com.intelligence.platform.entity.KnowledgeEntry> entryWrapper =
                    new LambdaQueryWrapper<>();
            entryWrapper.eq(com.intelligence.platform.entity.KnowledgeEntry::getDocumentId, doc.getId());
            List<com.intelligence.platform.entity.KnowledgeEntry> entries = knowledgeEntryMapper.selectList(entryWrapper);
            for (com.intelligence.platform.entity.KnowledgeEntry entry : entries) {
                entry.setTitle(newTitle);
                entry.setSourceName(newTitle);
                knowledgeEntryMapper.updateById(entry);
            }
        }

        return Map.of(
                "status", "success",
                "updated_count", updatedCount,
                "message", "批量重命名完成，共更新 " + updatedCount + " 个文档"
        );
    }

    /**
     * 修复孤儿知识词条：查找 documentId 指向不存在文档的 knowledge_entries 并清理
     * 同时从向量索引中移除。这是数据完整性修复端点，针对已产生的历史孤儿。
     */
    @PostMapping("/repair-orphan-entries")
    public Map<String, Object> repairOrphanEntries(
            @RequestParam(required = false) Long projectId,
            @RequestParam(defaultValue = "false") boolean dryRun) {
        
        // 1. 查询所有文档 ID
        List<Document> allDocs = documentMapper.selectList(null);
        java.util.Set<Long> validDocIds = new java.util.HashSet<>();
        for (Document d : allDocs) validDocIds.add(d.getId());

        // 2. 查询所有词条（有 documentId 的）
        LambdaQueryWrapper<com.intelligence.platform.entity.KnowledgeEntry> entryWrapper =
                new LambdaQueryWrapper<>();
        if (projectId != null) {
            entryWrapper.eq(com.intelligence.platform.entity.KnowledgeEntry::getProjectId, projectId);
        }
        List<com.intelligence.platform.entity.KnowledgeEntry> allEntries =
                knowledgeEntryMapper.selectList(entryWrapper);

        // 3. 找出孤儿
        List<Map<String, Object>> orphans = new ArrayList<>();
        for (com.intelligence.platform.entity.KnowledgeEntry entry : allEntries) {
            if (entry.getDocumentId() != null && !validDocIds.contains(entry.getDocumentId())) {
                orphans.add(Map.of(
                        "id", entry.getId(),
                        "title", entry.getTitle() != null ? entry.getTitle() : "",
                        "entry_type", entry.getEntryType() != null ? entry.getEntryType() : "",
                        "entry_library", entry.getEntryLibrary() != null ? entry.getEntryLibrary() : "",
                        "document_id", entry.getDocumentId(),
                        "project_id", entry.getProjectId() != null ? entry.getProjectId() : 0
                ));
            }
        }

        // 4. 清理
        int deleted = 0;
        if (!dryRun && !orphans.isEmpty()) {
            for (Map<String, Object> orphan : orphans) {
                Long entryId = ((Number) orphan.get("id")).longValue();
                try {
                    vectorSearchService.removeEntry(entryId);
                } catch (Exception ignored) {}
                knowledgeEntryMapper.deleteById(entryId);
                deleted++;
            }
        }

        log.info("Orphan repair: found {} orphans, dryRun={}, deleted={}", orphans.size(), dryRun, deleted);

        return Map.of(
                "status", "success",
                "orphan_count", orphans.size(),
                "deleted_count", deleted,
                "dry_run", dryRun,
                "orphans", orphans,
                "message", dryRun
                        ? "发现 " + orphans.size() + " 个孤儿词条（预览模式，未删除）"
                        : "已删除 " + deleted + " 个孤儿词条"
        );
    }
}
