package com.intelligence.platform.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.intelligence.platform.common.PageResult;
import com.intelligence.platform.entity.KnowledgeEntry;
import com.intelligence.platform.mapper.KnowledgeEntryMapper;
import com.intelligence.platform.service.ProjectContext;
import com.intelligence.platform.service.VectorSearchService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 知识词条管理（前台"研究报告"Tab核心数据源）
 */
@RestController
@RequestMapping("/api/knowledge-entries")
@CrossOrigin(origins = "*")
public class KnowledgeEntryController {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeEntryController.class);

    @Autowired
    private KnowledgeEntryMapper knowledgeEntryMapper;

    @Autowired
    private ProjectContext projectContext;

    @Autowired
    private VectorSearchService vectorSearchService;

    @Autowired
    private KGController kgController;

    @Autowired
    private com.intelligence.platform.mapper.ProjectMapper projectMapper;

    @Autowired
    private com.intelligence.platform.mapper.DocumentMapper documentMapper;

    /**
     * 分页查询词条
     * 前台"研究报告"Tab调用
     */
    @GetMapping
    public PageResult<KnowledgeEntry> list(
            @RequestParam(required = false) String library,
            @RequestParam(required = false) String entryType,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false, defaultValue = "false") boolean includeImage,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {

        LambdaQueryWrapper<KnowledgeEntry> wrapper = new LambdaQueryWrapper<>();
        // 项目隔离
        Long pid = projectContext.getCurrentProjectId();
        if (pid != null) wrapper.eq(KnowledgeEntry::getProjectId, pid);
        if (library != null && !library.isEmpty()) wrapper.eq(KnowledgeEntry::getEntryLibrary, library);
        if (entryType != null && !entryType.isEmpty()) wrapper.eq(KnowledgeEntry::getEntryType, entryType);
        if (status != null && !status.isEmpty()) wrapper.eq(KnowledgeEntry::getStatus, status);
        if (keyword != null && !keyword.isEmpty()) {
            wrapper.and(w -> w.like(KnowledgeEntry::getTitle, keyword)
                    .or().like(KnowledgeEntry::getContent, keyword)
                    .or().like(KnowledgeEntry::getKeywords, keyword));
        }
        // 默认排除图片和表格类型的词条（图表库内容在图表管理页单独展示）
        if (!includeImage) {
            wrapper.notIn(KnowledgeEntry::getEntryType, "image", "table");
        }
        wrapper.orderByDesc(KnowledgeEntry::getCreatedAt);

        Page<KnowledgeEntry> pageObj = new Page<>(page, pageSize);
        Page<KnowledgeEntry> result = knowledgeEntryMapper.selectPage(pageObj, wrapper);
        // 验证并过滤不存在的关联词条
        validateRelatedEntries(result.getRecords());
        return new PageResult<>(result.getTotal(), page, pageSize, result.getRecords());
    }

    /**
     * 按资料库统计词条数量（带项目隔离）
     */
    @GetMapping("/stats/by-library")
    public Map<String, Long> statsByLibrary() {
        Long pid = projectContext.getCurrentProjectId();

        LambdaQueryWrapper<KnowledgeEntry> reportW = new LambdaQueryWrapper<KnowledgeEntry>().eq(KnowledgeEntry::getEntryLibrary, "report");
        LambdaQueryWrapper<KnowledgeEntry> dynamicW = new LambdaQueryWrapper<KnowledgeEntry>().eq(KnowledgeEntry::getEntryLibrary, "dynamic");
        LambdaQueryWrapper<KnowledgeEntry> translationW = new LambdaQueryWrapper<KnowledgeEntry>().eq(KnowledgeEntry::getEntryLibrary, "translation");
        LambdaQueryWrapper<KnowledgeEntry> chartW = new LambdaQueryWrapper<KnowledgeEntry>().eq(KnowledgeEntry::getEntryLibrary, "chart");

        if (pid != null) {
            reportW.eq(KnowledgeEntry::getProjectId, pid);
            dynamicW.eq(KnowledgeEntry::getProjectId, pid);
            translationW.eq(KnowledgeEntry::getProjectId, pid);
            chartW.eq(KnowledgeEntry::getProjectId, pid);
        }

        long report = knowledgeEntryMapper.selectCount(reportW);
        long dynamic = knowledgeEntryMapper.selectCount(dynamicW);
        long translation = knowledgeEntryMapper.selectCount(translationW);
        long chart = knowledgeEntryMapper.selectCount(chartW);
        return Map.of("report", report, "dynamic", dynamic, "translation", translation, "chart", chart);
    }

    @GetMapping("/{id}")
    public KnowledgeEntry get(@PathVariable Long id) {
        KnowledgeEntry entry = knowledgeEntryMapper.selectById(id);
        if (entry != null) {
            validateRelatedEntry(entry);
        }
        return entry;
    }

    /**
     * 手动创建词条（后台管理员）
     * 创建成功后自动更新相关图表资料的标签（智能标签功能）
     */
    @PostMapping("/")
    public Map<String, Object> create(@RequestBody KnowledgeEntry entry) {
        // 创建时验证关联词条真实性
        validateRelatedEntry(entry);
        knowledgeEntryMapper.insert(entry);
        // 新词条创建后，自动更新相关图表资料的标签
        updateChartEntryTags(entry);
        syncKG();
        return Map.of("id", entry.getId(), "message", "创建成功");
    }

    /**
     * 新词条创建后，自动更新相关图表资料的标签
     * 根据新词条的关键词，匹配图表库中相关词条并更新其keywords字段
     * 匹配逻辑：新词条的关键词在图表词条的标题或内容中出现时，将该关键词追加到图表词条的标签中
     */
    private void updateChartEntryTags(KnowledgeEntry newEntry) {
        try {
            if (newEntry.getProjectId() == null) return;
            if ((newEntry.getKeywords() == null || newEntry.getKeywords().isEmpty())
                    && (newEntry.getTitle() == null || newEntry.getTitle().isEmpty())) {
                return;
            }

            // 查询同项目下所有图表库词条
            LambdaQueryWrapper<KnowledgeEntry> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(KnowledgeEntry::getProjectId, newEntry.getProjectId())
                   .eq(KnowledgeEntry::getEntryLibrary, "chart");
            List<KnowledgeEntry> chartEntries = knowledgeEntryMapper.selectList(wrapper);

            if (chartEntries.isEmpty()) return;

            // 收集新词条的关键词列表
            List<String> newKeywords = new ArrayList<>();
            if (newEntry.getKeywords() != null && !newEntry.getKeywords().isEmpty()) {
                for (String kw : newEntry.getKeywords().split(",")) {
                    String trimmed = kw.trim();
                    if (!trimmed.isEmpty() && trimmed.length() >= 2) {
                        newKeywords.add(trimmed);
                    }
                }
            }
            // 也将新词条标题作为匹配关键词
            if (newEntry.getTitle() != null && !newEntry.getTitle().isEmpty()) {
                newKeywords.add(newEntry.getTitle());
            }

            if (newKeywords.isEmpty()) return;

            int updatedCount = 0;
            for (KnowledgeEntry chartEntry : chartEntries) {
                // 构建图表词条的文本（标题+内容），用于关键词匹配
                String chartText = (chartEntry.getTitle() != null ? chartEntry.getTitle() : "") + " " +
                                   (chartEntry.getContent() != null ? chartEntry.getContent() : "");

                // 检查新词条的关键词是否与图表词条相关
                List<String> matchedTags = new ArrayList<>();
                for (String keyword : newKeywords) {
                    if (chartText.contains(keyword)) {
                        matchedTags.add(keyword);
                    }
                }

                if (!matchedTags.isEmpty()) {
                    String existingKeywords = chartEntry.getKeywords() != null ? chartEntry.getKeywords() : "";
                    StringBuilder merged = new StringBuilder(existingKeywords);

                    for (String tag : matchedTags) {
                        if (!existingKeywords.contains(tag)) {
                            if (merged.length() > 0) merged.append(",");
                            merged.append(tag);
                        }
                    }

                    if (!merged.toString().equals(existingKeywords)) {
                        chartEntry.setKeywords(merged.toString());
                        knowledgeEntryMapper.updateById(chartEntry);
                        updatedCount++;
                        log.info("Smart tags: updated chart entry '{}' (id={}) with tags: {}",
                                chartEntry.getTitle(), chartEntry.getId(), merged);
                    }
                }
            }

            if (updatedCount > 0) {
                log.info("Smart tags: updated {} chart entries based on new entry '{}'",
                        updatedCount, newEntry.getTitle());
            }
        } catch (Exception e) {
            // 标签更新失败不影响主流程
            log.warn("Smart tags: failed to update chart entry tags: {}", e.getMessage());
        }
    }

    /**
     * 审核词条（后台管理员）
     */
    @PutMapping("/{id}/review")
    public Map<String, Object> review(@PathVariable Long id,
                                      @RequestParam String status,
                                      @RequestParam(required = false) String reviewer) {
        KnowledgeEntry entry = new KnowledgeEntry();
        entry.setId(id);
        entry.setStatus(status);
        entry.setReviewer(reviewer);
        knowledgeEntryMapper.updateById(entry);
        syncKG();
        return Map.of("message", "审核完成");
    }

    @PutMapping("/{id}")
    public Map<String, Object> update(@PathVariable Long id, @RequestBody KnowledgeEntry entry) {
        entry.setId(id);
        // 读取旧值用于对比关联变化
        KnowledgeEntry oldEntry = knowledgeEntryMapper.selectById(id);
        String oldRelated = oldEntry != null ? oldEntry.getRelated() : null;
        
        // 更新时验证关联词条真实性
        validateRelatedEntry(entry);
        knowledgeEntryMapper.updateById(entry);
        
        // 同步反向关联
        syncBidirectionalRelations(entry, oldRelated);
        
        syncKG();
        return Map.of("message", "更新成功");
    }

    /**
     * 更新词条的表格Markdown内容
     */
    @PutMapping("/{id}/table-markdown")
    public Map<String, Object> updateTableMarkdown(
            @PathVariable Long id,
            @RequestBody Map<String, String> body) {

        String tableMarkdown = body.get("tableMarkdown");
        if (tableMarkdown == null) {
            return Map.of("status", "error", "message", "tableMarkdown is required");
        }

        KnowledgeEntry entry = knowledgeEntryMapper.selectById(id);
        if (entry == null) {
            return Map.of("status", "error", "message", "Entry not found");
        }

        entry.setTableMarkdown(tableMarkdown);
        knowledgeEntryMapper.updateById(entry);
        syncKG();

        // 重新索引向量搜索，确保编辑后的表格内容可被 Q&A 检索
        try {
            vectorSearchService.indexEntry(entry);
        } catch (Exception e) {
            // 索引失败不影响数据库更新结果，仅记录警告
            // 可通过重建索引修复
        }

        return Map.of("status", "success", "entry", entry);
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable Long id) {
        knowledgeEntryMapper.deleteById(id);
        try {
            vectorSearchService.removeEntry(id);
        } catch (Exception ignored) {}
        syncKG();
        return Map.of("message", "删除成功");
    }

    /**
     * 批量审核
     */
    @PutMapping("/batch-review")
    public Map<String, Object> batchReview(@RequestBody Map<String, Object> body) {
        String status = (String) body.get("status");
        String reviewer = (String) body.get("reviewer");
        @SuppressWarnings("unchecked")
        List<Number> ids = (List<Number>) body.get("ids");
        for (Number idNum : ids) {
            KnowledgeEntry entry = new KnowledgeEntry();
            entry.setId(idNum.longValue());
            entry.setStatus(status);
            entry.setReviewer(reviewer);
            knowledgeEntryMapper.updateById(entry);
        }
        syncKG();
        return Map.of("message", "批量审核完成，共" + ids.size() + "条");
    }

    /**
     * 修复所有词条的关联词条：过滤掉不存在的关联标题
     * 用于清理 LLM 提取时生成的无效关联词条
     */
    @PostMapping("/repair-related")
    public Map<String, Object> repairRelated() {
        Long pid = projectContext.getCurrentProjectId();
        LambdaQueryWrapper<KnowledgeEntry> wrapper = new LambdaQueryWrapper<>();
        if (pid != null) wrapper.eq(KnowledgeEntry::getProjectId, pid);
        wrapper.isNotNull(KnowledgeEntry::getRelated).ne(KnowledgeEntry::getRelated, "");
        List<KnowledgeEntry> entries = knowledgeEntryMapper.selectList(wrapper);

        int totalChecked = entries.size();
        int totalFixed = 0;
        for (KnowledgeEntry entry : entries) {
            String original = entry.getRelated();
            validateRelatedEntry(entry);
            String cleaned = entry.getRelated();
            if (original != null && !original.equals(cleaned == null ? "" : cleaned)) {
                knowledgeEntryMapper.updateById(entry);
                totalFixed++;
                log.info("修复关联词条: '{}' (id={}) {} -> {}", entry.getTitle(), entry.getId(), original, cleaned);
            }
        }

        return Map.of(
            "message", "关联词条修复完成",
            "total_checked", totalChecked,
            "total_fixed", totalFixed
        );
    }

    /**
     * 修复所有已有词条的双向关联关系
     * 确保 A.related 包含 B 时，B.related 也包含 A
     */
    @PostMapping("/repair-bidirectional")
    public Map<String, Object> repairBidirectional() {
        Long pid = projectContext.getCurrentProjectId();
        LambdaQueryWrapper<KnowledgeEntry> wrapper = new LambdaQueryWrapper<>();
        if (pid != null) wrapper.eq(KnowledgeEntry::getProjectId, pid);
        List<KnowledgeEntry> allEntries = knowledgeEntryMapper.selectList(wrapper);

        // title -> Set<relatedTitle>
        Map<String, Set<String>> existingMap = new HashMap<>();
        Map<Long, String> idToTitle = new HashMap<>();
        
        for (KnowledgeEntry e : allEntries) {
            if (e.getTitle() != null && !e.getTitle().isEmpty()) {
                idToTitle.put(e.getId(), e.getTitle());
                existingMap.put(e.getTitle(), parseRelatedSet(e.getRelated()));
            }
        }

        // 计算需要添加的反向关联 (targetTitle -> Set<sourceTitles>)
        Map<String, Set<String>> toAdd = new HashMap<>();
        
        for (KnowledgeEntry entry : allEntries) {
            if (entry.getRelated() == null || entry.getRelated().isEmpty()) continue;
            if (entry.getTitle() == null || entry.getTitle().isEmpty()) continue;
            
            Set<String> myRelated = parseRelatedSet(entry.getRelated());
            for (String relTitle : myRelated) {
                Set<String> targetRelated = existingMap.get(relTitle);
                if (targetRelated == null) continue; // 目标词条不存在
                if (!targetRelated.contains(entry.getTitle())) {
                    toAdd.computeIfAbsent(relTitle, k -> new HashSet<>())
                         .add(entry.getTitle());
                }
            }
        }

        // 批量更新
        int totalFixed = 0;
        for (Map.Entry<String, Set<String>> e : toAdd.entrySet()) {
            String targetTitle = e.getKey();
            Set<String> titlesToAdd = e.getValue();
            
            Set<String> current = existingMap.get(targetTitle);
            current.addAll(titlesToAdd);
            
            LambdaQueryWrapper<KnowledgeEntry> updateWrapper = new LambdaQueryWrapper<>();
            updateWrapper.eq(KnowledgeEntry::getTitle, targetTitle);
            if (pid != null) updateWrapper.eq(KnowledgeEntry::getProjectId, pid);
            
            KnowledgeEntry update = new KnowledgeEntry();
            update.setRelated(String.join(",", current));
            knowledgeEntryMapper.update(update, updateWrapper);
            totalFixed += titlesToAdd.size();
            
            log.info("双向修复: 为 '{}' 补充关联 → {}", targetTitle, String.join(", ", titlesToAdd));
        }

        return Map.of(
            "message", "双向关联修复完成",
            "total_checked", allEntries.size(),
            "total_fixed", totalFixed
        );
    }

    /**
     * 修复历史数据的 project_id（数据迁移：回填 NULL project_id）
     * 通过 document_id 关联 documents 表回填 project_id
     * 无法通过 document 回溯的孤立记录：若系统仅有单个项目则兜底归入，否则保留 NULL
     */
    @PostMapping("/repair-project-id")
    public Map<String, Object> repairProjectId() {
        // 1. 查询 project_id IS NULL 的全部 knowledge_entries
        LambdaQueryWrapper<KnowledgeEntry> wrapper = new LambdaQueryWrapper<>();
        wrapper.isNull(KnowledgeEntry::getProjectId);
        List<KnowledgeEntry> orphanEntries = knowledgeEntryMapper.selectList(wrapper);

        int totalScanned = orphanEntries.size();
        int totalRepaired = 0;
        int totalOrphan = 0;

        // 2. 查询系统所有项目，判断是否仅有单个项目用于兜底
        List<com.intelligence.platform.entity.Project> allProjects = projectMapper.selectList(null);
        Long fallbackProjectId = null;
        if (allProjects.size() == 1) {
            fallbackProjectId = allProjects.get(0).getId();
        }

        // 3. 逐条回填
        for (KnowledgeEntry entry : orphanEntries) {
            Long targetProjectId = null;
            // 优先通过 document_id 关联回溯
            if (entry.getDocumentId() != null) {
                com.intelligence.platform.entity.Document doc = documentMapper.selectById(entry.getDocumentId());
                if (doc != null && doc.getProjectId() != null) {
                    targetProjectId = doc.getProjectId();
                }
            }
            // document 回溯失败且系统仅单个项目时兜底
            if (targetProjectId == null) {
                targetProjectId = fallbackProjectId;
            }
            if (targetProjectId != null) {
                entry.setProjectId(targetProjectId);
                knowledgeEntryMapper.updateById(entry);
                totalRepaired++;
                log.info("Repair project_id: entry '{}' (id={}) -> projectId={}",
                        entry.getTitle(), entry.getId(), targetProjectId);

                // 同步回填关联 document 的 project_id（如果 document 本身也为 NULL）
                if (entry.getDocumentId() != null) {
                    com.intelligence.platform.entity.Document doc = documentMapper.selectById(entry.getDocumentId());
                    if (doc != null && doc.getProjectId() == null) {
                        doc.setProjectId(targetProjectId);
                        documentMapper.updateById(doc);
                        log.info("Repair project_id: document '{}' (id={}) -> projectId={}",
                                doc.getTitle(), doc.getId(), targetProjectId);
                    }
                }
            } else {
                totalOrphan++;
                log.warn("Repair project_id: entry '{}' (id={}) 无法确定归属项目，保留 NULL",
                        entry.getTitle(), entry.getId());
            }
        }

        return Map.of(
            "message", "project_id 修复完成",
            "total_scanned", totalScanned,
            "total_repaired", totalRepaired,
            "total_orphan", totalOrphan
        );
    }

    private void syncKG() {
        try {
            kgController.buildGraph();
        } catch (Exception e) {
            // ignore
        }
    }

    /**
     * 验证单个词条的related字段，过滤不存在的关联词条
     */
    private void validateRelatedEntry(KnowledgeEntry entry) {
        if (entry.getRelated() == null || entry.getRelated().isEmpty()) return;
        
        String[] relatedTitles = entry.getRelated().split(",");
        List<String> validTitles = new ArrayList<>();
        
        for (String title : relatedTitles) {
            String trimmed = title.trim();
            if (trimmed.isEmpty()) continue;
            
            LambdaQueryWrapper<KnowledgeEntry> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(KnowledgeEntry::getTitle, trimmed);
            if (entry.getProjectId() != null) {
                wrapper.eq(KnowledgeEntry::getProjectId, entry.getProjectId());
            }
            if (knowledgeEntryMapper.selectCount(wrapper) > 0) {
                validTitles.add(trimmed);
            }
        }
        
        entry.setRelated(validTitles.isEmpty() ? null : String.join(",", validTitles));
    }

    /**
     * 批量验证词条列表的related字段，过滤不存在的关联词条
     */
    private void validateRelatedEntries(List<KnowledgeEntry> entries) {
        for (KnowledgeEntry entry : entries) {
            validateRelatedEntry(entry);
        }
    }

    /**
     * 同步反向关联：确保关联关系双向存储。
     * 当 A 关联 B 时，B 的 related 字段也必须包含 A。
     */
    private void syncBidirectionalRelations(KnowledgeEntry entry, String oldRelated) {
        String newRelated = entry.getRelated();
        
        // 解析新旧关联词条标题集合
        Set<String> oldSet = parseRelatedSet(oldRelated);
        Set<String> newSet = parseRelatedSet(newRelated);
        
        // 新增的关联：需要反向添加到目标词条
        Set<String> added = new HashSet<>(newSet);
        added.removeAll(oldSet);
        
        // 移除的关联：需要从目标词条中反向删除
        Set<String> removed = new HashSet<>(oldSet);
        removed.removeAll(newSet);
        
        Long pid = entry.getProjectId();
        
        // 处理新增的关联
        for (String title : added) {
            KnowledgeEntry target = findEntryByTitle(title, pid);
            if (target != null) {
                Set<String> targetRelated = parseRelatedSet(target.getRelated());
                if (!targetRelated.contains(entry.getTitle())) {
                    targetRelated.add(entry.getTitle());
                    target.setRelated(String.join(",", targetRelated));
                    knowledgeEntryMapper.updateById(target);
                }
            }
        }
        
        // 处理移除的关联
        for (String title : removed) {
            KnowledgeEntry target = findEntryByTitle(title, pid);
            if (target != null) {
                Set<String> targetRelated = parseRelatedSet(target.getRelated());
                if (targetRelated.contains(entry.getTitle())) {
                    targetRelated.remove(entry.getTitle());
                    target.setRelated(targetRelated.isEmpty() ? null : String.join(",", targetRelated));
                    knowledgeEntryMapper.updateById(target);
                }
            }
        }
    }

    private Set<String> parseRelatedSet(String related) {
        if (related == null || related.trim().isEmpty()) return new HashSet<>();
        return new HashSet<>(Arrays.asList(related.split("\\s*,\\s*")));
    }

    private KnowledgeEntry findEntryByTitle(String title, Long projectId) {
        LambdaQueryWrapper<KnowledgeEntry> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(KnowledgeEntry::getTitle, title);
        if (projectId != null) wrapper.eq(KnowledgeEntry::getProjectId, projectId);
        return knowledgeEntryMapper.selectOne(wrapper);
    }
}
