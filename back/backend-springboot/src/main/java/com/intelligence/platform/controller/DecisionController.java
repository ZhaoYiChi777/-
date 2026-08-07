package com.intelligence.platform.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.intelligence.platform.common.PageResult;
import com.intelligence.platform.entity.Decision;
import com.intelligence.platform.mapper.DecisionMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import com.intelligence.platform.common.Result;
import com.intelligence.platform.service.ProjectContext;

import java.util.Map;

@RestController
@RequestMapping("/api/decisions")
@CrossOrigin(origins = "*")
public class DecisionController {

    @Autowired
    private DecisionMapper decisionMapper;

    @Autowired
    private ProjectContext projectContext;

    @GetMapping
    public PageResult<Decision> listDecisions(
            @RequestParam(required = false) String decisionType,
            @RequestParam(required = false) String category,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {

        LambdaQueryWrapper<Decision> wrapper = new LambdaQueryWrapper<>();
        if (decisionType != null && !decisionType.isEmpty()) wrapper.eq(Decision::getDecisionType, decisionType);
        if (category != null && !category.isEmpty()) wrapper.eq(Decision::getCategory, category);
        wrapper.orderByDesc(Decision::getScore).orderByDesc(Decision::getCreatedAt);

        Page<Decision> pageObj = new Page<>(page, pageSize);
        Page<Decision> result = decisionMapper.selectPage(pageObj, wrapper);
        return new PageResult<>(result.getTotal(), page, pageSize, result.getRecords());
    }

    @PostMapping("/")
    public Map<String, Object> createDecision(@RequestBody Decision decision) {
        decisionMapper.insert(decision);
        return Map.of("id", decision.getId(), "message", "创建成功");
    }

    @PutMapping("/{id}")
    public Result<?> updateDecision(@PathVariable Long id, @RequestBody Decision decision) {
        decision.setId(id);
        decisionMapper.updateById(decision);
        return Result.ok("更新成功");
    }

    @GetMapping("/{id}")
    public Result<?> getDecision(@PathVariable Long id) {
        return Result.ok(decisionMapper.selectById(id));
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> deleteDecision(@PathVariable Long id) {
        decisionMapper.deleteById(id);
        return Map.of("message", "删除成功");
    }
}
