package com.intelligence.platform.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.intelligence.platform.common.PageResult;
import com.intelligence.platform.entity.RiskAlert;
import com.intelligence.platform.mapper.RiskAlertMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import com.intelligence.platform.common.Result;
import com.intelligence.platform.service.ProjectContext;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/risks")
@CrossOrigin(origins = "*")
public class RiskController {

    @Autowired
    private RiskAlertMapper riskAlertMapper;

    @Autowired
    private ProjectContext projectContext;

    @GetMapping
    public PageResult<RiskAlert> listRisks(
            @RequestParam(required = false) String severity,
            @RequestParam(required = false) String category,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {

        LambdaQueryWrapper<RiskAlert> wrapper = new LambdaQueryWrapper<>();
        Long projectId = projectContext.getCurrentProjectId();
        if (projectId != null) wrapper.eq(RiskAlert::getProjectId, projectId);
        if (severity != null && !severity.isEmpty()) wrapper.eq(RiskAlert::getSeverity, severity);
        if (category != null && !category.isEmpty()) wrapper.eq(RiskAlert::getCategory, category);
        wrapper.orderByDesc(RiskAlert::getDetectedAt);

        Page<RiskAlert> pageObj = new Page<>(page, pageSize);
        Page<RiskAlert> result = riskAlertMapper.selectPage(pageObj, wrapper);
        return new PageResult<>(result.getTotal(), page, pageSize, result.getRecords());
    }

    @GetMapping("/stats")
    public Map<String, Object> riskStats() {
        Map<String, Object> stats = new HashMap<>();
        long total = riskAlertMapper.selectCount(null);
        List<RiskAlert> all = riskAlertMapper.selectList(null);
        Map<String, Long> bySeverity = all.stream()
                .collect(Collectors.groupingBy(RiskAlert::getSeverity, Collectors.counting()));
        stats.put("total", total);
        stats.put("by_severity", bySeverity);
        return stats;
    }

    @PostMapping("/")
    public Map<String, Object> createRisk(@RequestBody RiskAlert alert) {
        riskAlertMapper.insert(alert);
        return Map.of("id", alert.getId(), "message", "创建成功");
    }

    @PutMapping("/{id}")
    public Result<?> updateRiskAlert(@PathVariable Long id, @RequestBody RiskAlert riskAlert) {
        riskAlert.setId(id);
        riskAlertMapper.updateById(riskAlert);
        return Result.ok("更新成功");
    }

    @GetMapping("/{id}")
    public Result<?> getRiskAlert(@PathVariable Long id) {
        return Result.ok(riskAlertMapper.selectById(id));
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> deleteRisk(@PathVariable Long id) {
        riskAlertMapper.deleteById(id);
        return Map.of("message", "删除成功");
    }
}
