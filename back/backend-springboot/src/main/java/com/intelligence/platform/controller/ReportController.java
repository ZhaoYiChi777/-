package com.intelligence.platform.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.intelligence.platform.common.PageResult;
import com.intelligence.platform.entity.Report;
import com.intelligence.platform.mapper.ReportMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import com.intelligence.platform.common.Result;
import com.intelligence.platform.service.ProjectContext;

import java.util.Map;

@RestController
@RequestMapping("/api/reports")
@CrossOrigin(origins = "*")
public class ReportController {

    @Autowired
    private ReportMapper reportMapper;

    @Autowired
    private ProjectContext projectContext;

    @GetMapping
    public PageResult<Report> listReports(
            @RequestParam(required = false) String reportType,
            @RequestParam(required = false) String categoryL1,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {

        LambdaQueryWrapper<Report> wrapper = new LambdaQueryWrapper<>();
        Long projectId = projectContext.getCurrentProjectId();
        if (projectId != null) wrapper.eq(Report::getProjectId, projectId);
        if (reportType != null && !reportType.isEmpty()) wrapper.eq(Report::getReportType, reportType);
        if (categoryL1 != null && !categoryL1.isEmpty()) wrapper.eq(Report::getCategoryL1, categoryL1);
        if (status != null && !status.isEmpty()) wrapper.eq(Report::getStatus, status);
        if (keyword != null && !keyword.isEmpty()) {
            wrapper.and(w -> w.like(Report::getTitle, keyword).or().like(Report::getSummary, keyword));
        }
        wrapper.orderByDesc(Report::getUploadTime);

        Page<Report> pageObj = new Page<>(page, pageSize);
        Page<Report> result = reportMapper.selectPage(pageObj, wrapper);
        return new PageResult<>(result.getTotal(), page, pageSize, result.getRecords());
    }

    @GetMapping("/{id}")
    public Report getReport(@PathVariable Long id) {
        return reportMapper.selectById(id);
    }

    @PostMapping("/")
    public Map<String, Object> createReport(@RequestBody Report report) {
        reportMapper.insert(report);
        return Map.of("id", report.getId(), "message", "创建成功");
    }

    @PutMapping("/{id}")
    public Result<?> updateReport(@PathVariable Long id, @RequestBody Report report) {
        report.setId(id);
        reportMapper.updateById(report);
        return Result.ok("更新成功");
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> deleteReport(@PathVariable Long id) {
        reportMapper.deleteById(id);
        return Map.of("message", "删除成功");
    }
}
