package com.intelligence.platform.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.intelligence.platform.common.PageResult;
import com.intelligence.platform.entity.QARecord;
import com.intelligence.platform.mapper.QARecordMapper;
import com.intelligence.platform.service.ProjectContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/qa")
@CrossOrigin(origins = "*")
public class QAController {

    @Autowired
    private QARecordMapper qaRecordMapper;
    @Autowired
    private ProjectContext projectContext;

    @GetMapping
    public PageResult<QARecord> listQA(
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {

        Long projectId = projectContext.getCurrentProjectId();
        LambdaQueryWrapper<QARecord> wrapper = new LambdaQueryWrapper<>();
        if (projectId != null) wrapper.eq(QARecord::getProjectId, projectId);
        if (category != null && !category.isEmpty()) wrapper.eq(QARecord::getCategory, category);
        if (keyword != null && !keyword.isEmpty()) {
            wrapper.and(w -> w.like(QARecord::getQuestion, keyword).or().like(QARecord::getAnswer, keyword));
        }
        wrapper.orderByDesc(QARecord::getCreatedAt);

        Page<QARecord> pageObj = new Page<>(page, pageSize);
        Page<QARecord> result = qaRecordMapper.selectPage(pageObj, wrapper);
        return new PageResult<>(result.getTotal(), page, pageSize, result.getRecords());
    }

    @GetMapping("/stats")
    public Map<String, Object> qaStats() {
        Long projectId = projectContext.getCurrentProjectId();
        LambdaQueryWrapper<QARecord> wrapper = new LambdaQueryWrapper<>();
        if (projectId != null) wrapper.eq(QARecord::getProjectId, projectId);
        
        long total = qaRecordMapper.selectCount(wrapper);
        List<QARecord> all = qaRecordMapper.selectList(wrapper);
        double avgConf = all.stream()
                .mapToDouble(r -> r.getConfidence() != null ? r.getConfidence() : 0.0)
                .average().orElse(0.0);
        Map<String, Object> stats = new HashMap<>();
        stats.put("total", total);
        stats.put("avg_confidence", Math.round(avgConf * 1000.0) / 1000.0);
        return stats;
    }

    @GetMapping("/sessions")
    public List<Map<String, Object>> listSessions() {
        Long projectId = projectContext.getCurrentProjectId();
        LambdaQueryWrapper<QARecord> wrapper = new LambdaQueryWrapper<QARecord>()
                .ne(QARecord::getSessionId, "");
        if (projectId != null) wrapper.eq(QARecord::getProjectId, projectId);
        wrapper.orderByAsc(QARecord::getCreatedAt);
        
        List<QARecord> all = qaRecordMapper.selectList(wrapper);
        return all.stream()
                .filter(r -> r.getSessionId() != null && !r.getSessionId().isEmpty())
                .collect(Collectors.groupingBy(QARecord::getSessionId))
                .entrySet().stream()
                .map(e -> {
                    Map<String, Object> session = new HashMap<>();
                    session.put("session_id", e.getKey());
                    session.put("title", e.getValue().get(0).getQuestion());
                    session.put("first_msg", e.getValue().get(0).getCreatedAt());
                    session.put("msg_count", e.getValue().size());
                    return session;
                })
                .collect(Collectors.toList());
    }

    @GetMapping("/session/{sessionId}")
    public List<QARecord> getSession(@PathVariable String sessionId) {
        Long projectId = projectContext.getCurrentProjectId();
        LambdaQueryWrapper<QARecord> wrapper = new LambdaQueryWrapper<QARecord>()
                .eq(QARecord::getSessionId, sessionId);
        if (projectId != null) wrapper.eq(QARecord::getProjectId, projectId);
        wrapper.orderByAsc(QARecord::getCreatedAt);
        return qaRecordMapper.selectList(wrapper);
    }

    @DeleteMapping("/session/{sessionId}")
    public Map<String, Object> deleteSession(@PathVariable String sessionId) {
        Long projectId = projectContext.getCurrentProjectId();
        LambdaQueryWrapper<QARecord> wrapper = new LambdaQueryWrapper<QARecord>()
                .eq(QARecord::getSessionId, sessionId);
        if (projectId != null) wrapper.eq(QARecord::getProjectId, projectId);
        qaRecordMapper.delete(wrapper);
        return Map.of("message", "删除成功");
    }

    @PostMapping("/")
    public Map<String, Object> createQA(@RequestBody QARecord qa) {
        if (qa.getProjectId() == null) {
            qa.setProjectId(projectContext.getCurrentProjectId());
        }
        qaRecordMapper.insert(qa);
        return Map.of("id", qa.getId(), "message", "创建成功");
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> deleteQA(@PathVariable Long id) {
        qaRecordMapper.deleteById(id);
        return Map.of("message", "删除成功");
    }
}
