package com.intelligence.platform.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.intelligence.platform.common.PageResult;
import com.intelligence.platform.entity.AnalysisReport;
import com.intelligence.platform.mapper.AnalysisReportMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import com.intelligence.platform.service.ProjectContext;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/analysis")
@CrossOrigin(origins = "*")
public class AnalysisController {

    @Autowired
    private AnalysisReportMapper analysisReportMapper;

    @Autowired
    private ProjectContext projectContext;

    @GetMapping
    public PageResult<AnalysisReport> listAnalysis(
            @RequestParam(required = false) String analysisType,
            @RequestParam(required = false) String categoryL1,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {

        LambdaQueryWrapper<AnalysisReport> wrapper = new LambdaQueryWrapper<>();
        Long projectId = projectContext.getCurrentProjectId();
        if (projectId != null) wrapper.eq(AnalysisReport::getProjectId, projectId);
        if (analysisType != null && !analysisType.isEmpty()) wrapper.eq(AnalysisReport::getAnalysisType, analysisType);
        if (categoryL1 != null && !categoryL1.isEmpty()) wrapper.eq(AnalysisReport::getCategoryL1, categoryL1);
        if (status != null && !status.isEmpty()) wrapper.eq(AnalysisReport::getStatus, status);
        wrapper.orderByDesc(AnalysisReport::getCreatedAt);

        Page<AnalysisReport> pageObj = new Page<>(page, pageSize);
        Page<AnalysisReport> result = analysisReportMapper.selectPage(pageObj, wrapper);
        return new PageResult<>(result.getTotal(), page, pageSize, result.getRecords());
    }

    @GetMapping("/stats")
    public Map<String, Object> analysisStats() {
        Map<String, Object> stats = new HashMap<>();
        long total = analysisReportMapper.selectCount(null);
        List<AnalysisReport> all = analysisReportMapper.selectList(null);
        Map<String, Long> byType = all.stream()
                .collect(Collectors.groupingBy(AnalysisReport::getAnalysisType, Collectors.counting()));
        stats.put("total", total);
        stats.put("by_type", byType);
        return stats;
    }

    @GetMapping("/{id}")
    public AnalysisReport getAnalysis(@PathVariable Long id) {
        return analysisReportMapper.selectById(id);
    }

    @PostMapping("/")
    public Map<String, Object> createAnalysis(@RequestBody AnalysisReport report) {
        analysisReportMapper.insert(report);
        return Map.of("id", report.getId(), "message", "创建成功");
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> deleteAnalysis(@PathVariable Long id) {
        analysisReportMapper.deleteById(id);
        return Map.of("message", "删除成功");
    }
}
