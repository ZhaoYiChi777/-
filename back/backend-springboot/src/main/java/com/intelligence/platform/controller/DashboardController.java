package com.intelligence.platform.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.intelligence.platform.entity.Document;
import com.intelligence.platform.entity.Project;
import com.intelligence.platform.mapper.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/dashboard")
@CrossOrigin(origins = "*")
public class DashboardController {

    @Autowired
    private DocumentMapper documentMapper;
    @Autowired
    private AnalysisReportMapper analysisReportMapper;
    @Autowired
    private QARecordMapper qaRecordMapper;
    @Autowired
    private ProjectMapper projectMapper;
    @Autowired
    private KGNodeMapper kgNodeMapper;

    @GetMapping("/stats")
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new HashMap<>();
        long docCount = documentMapper.selectCount(null);
        long reportCount = analysisReportMapper.selectCount(null);
        long qaCount = qaRecordMapper.selectCount(null);
        long projectCount = projectMapper.selectCount(
                new LambdaQueryWrapper<Project>().eq(Project::getStatus, "active"));
        long allProjects = projectMapper.selectCount(null);
        long kgNodeCount = kgNodeMapper.selectCount(null);

        stats.put("doc_count", docCount);
        stats.put("document_count", docCount);
        stats.put("report_count", reportCount);
        stats.put("qa_count", qaCount);
        stats.put("project_count", projectCount);
        stats.put("active_projects", allProjects);
        stats.put("kg_node_count", kgNodeCount);
        return stats;
    }

    @GetMapping("/latest-documents")
    public List<Document> getLatestDocuments(
            @RequestParam(defaultValue = "5") int limit) {
        LambdaQueryWrapper<Document> wrapper = new LambdaQueryWrapper<>();
        wrapper.orderByDesc(Document::getUploadTime).last("LIMIT " + limit);
        return documentMapper.selectList(wrapper);
    }

    @GetMapping("/active-projects")
    public List<Project> getActiveProjects(
            @RequestParam(defaultValue = "5") int limit) {
        LambdaQueryWrapper<Project> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Project::getStatus, "active")
                .orderByDesc(Project::getCreatedAt)
                .last("LIMIT " + limit);
        return projectMapper.selectList(wrapper);
    }
}
