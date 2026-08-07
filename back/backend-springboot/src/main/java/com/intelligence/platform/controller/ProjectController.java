package com.intelligence.platform.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.intelligence.platform.entity.Project;
import com.intelligence.platform.mapper.ProjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import com.intelligence.platform.common.Result;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * 项目管理控制器（参考 llm_wiki WikiProject）
 * 每个项目拥有独立的数据空间，数据不互通
 */
@RestController
@RequestMapping("/api/projects")
@CrossOrigin(origins = "*")
public class ProjectController {

    @Autowired
    private ProjectMapper projectMapper;

    @Autowired
    private com.intelligence.platform.mapper.DocumentMapper documentMapper;

    @Autowired
    private com.intelligence.platform.mapper.KnowledgeEntryMapper knowledgeEntryMapper;

    @Autowired
    private com.intelligence.platform.mapper.QARecordMapper qaRecordMapper;

    @Autowired
    private com.intelligence.platform.mapper.ReportMapper reportMapper;

    @GetMapping
    public Map<String, Object> listProjects() {
        LambdaQueryWrapper<Project> wrapper = new LambdaQueryWrapper<>();
        wrapper.orderByDesc(Project::getCreatedAt);
        List<Project> projects = projectMapper.selectList(wrapper);
        return Map.of("items", projects, "total", projects.size());
    }

    @GetMapping("/{id}")
    public Project getProject(@PathVariable Long id) {
        Project project = projectMapper.selectById(id);
        if (project == null) throw new RuntimeException("项目不存在");
        return project;
    }

    @PostMapping("/")
    public Map<String, Object> createProject(@RequestBody Project project) {
        project.setStatus("active");
        if (project.getCreatedAt() == null) {
            project.setCreatedAt(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        }
        projectMapper.insert(project);
        return Map.of("id", project.getId(), "message", "创建成功");
    }

    @PutMapping("/{id}")
    public Result<?> updateProject(@PathVariable Long id, @RequestBody Project project) {
        project.setId(id);
        projectMapper.updateById(project);
        return Result.ok("更新成功");
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> deleteProject(@PathVariable Long id) {
        projectMapper.deleteById(id);
        return Map.of("message", "删除成功");
    }

    /**
     * 获取项目详情（包含文档、词条、问答等统计信息）
     */
    @GetMapping("/{id}/detail")
    public Map<String, Object> getProjectDetail(@PathVariable Long id) {
        Project project = projectMapper.selectById(id);
        if (project == null) throw new RuntimeException("项目不存在");

        // 统计文档数
        LambdaQueryWrapper<com.intelligence.platform.entity.Document> docWrapper = new LambdaQueryWrapper<>();
        docWrapper.eq(com.intelligence.platform.entity.Document::getProjectId, id);
        long docCount = documentMapper.selectCount(docWrapper);

        // 统计词条数
        LambdaQueryWrapper<com.intelligence.platform.entity.KnowledgeEntry> entryWrapper = new LambdaQueryWrapper<>();
        entryWrapper.eq(com.intelligence.platform.entity.KnowledgeEntry::getProjectId, id);
        long entryCount = knowledgeEntryMapper.selectCount(entryWrapper);

        // 统计问答数
        LambdaQueryWrapper<com.intelligence.platform.entity.QARecord> qaWrapper = new LambdaQueryWrapper<>();
        qaWrapper.eq(com.intelligence.platform.entity.QARecord::getProjectId, id);
        long qaCount = qaRecordMapper.selectCount(qaWrapper);

        // 统计报告数
        LambdaQueryWrapper<com.intelligence.platform.entity.Report> reportWrapper = new LambdaQueryWrapper<>();
        reportWrapper.eq(com.intelligence.platform.entity.Report::getProjectId, id);
        long reportCount = reportMapper.selectCount(reportWrapper);

        // 获取最近的文档列表
        LambdaQueryWrapper<com.intelligence.platform.entity.Document> recentDocWrapper = new LambdaQueryWrapper<>();
        recentDocWrapper.eq(com.intelligence.platform.entity.Document::getProjectId, id)
                .orderByDesc(com.intelligence.platform.entity.Document::getUploadTime)
                .last("LIMIT 20");
        List<com.intelligence.platform.entity.Document> recentDocs = documentMapper.selectList(recentDocWrapper);

        // 获取最近的词条列表
        LambdaQueryWrapper<com.intelligence.platform.entity.KnowledgeEntry> recentEntryWrapper = new LambdaQueryWrapper<>();
        recentEntryWrapper.eq(com.intelligence.platform.entity.KnowledgeEntry::getProjectId, id)
                .orderByDesc(com.intelligence.platform.entity.KnowledgeEntry::getId)
                .last("LIMIT 20");
        List<com.intelligence.platform.entity.KnowledgeEntry> recentEntries = knowledgeEntryMapper.selectList(recentEntryWrapper);

        // 获取最近的问答记录
        LambdaQueryWrapper<com.intelligence.platform.entity.QARecord> recentQaWrapper = new LambdaQueryWrapper<>();
        recentQaWrapper.eq(com.intelligence.platform.entity.QARecord::getProjectId, id)
                .orderByDesc(com.intelligence.platform.entity.QARecord::getCreatedAt)
                .last("LIMIT 10");
        List<com.intelligence.platform.entity.QARecord> recentQa = qaRecordMapper.selectList(recentQaWrapper);

        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("id", project.getId());
        result.put("name", project.getName());
        result.put("description", project.getDescription());
        result.put("status", project.getStatus());
        result.put("createdAt", project.getCreatedAt());
        result.put("docCount", docCount);
        result.put("entryCount", entryCount);
        result.put("qaCount", qaCount);
        result.put("reportCount", reportCount);
        result.put("documents", recentDocs);
        result.put("entries", recentEntries);
        result.put("qaRecords", recentQa);

        return result;
    }
}
