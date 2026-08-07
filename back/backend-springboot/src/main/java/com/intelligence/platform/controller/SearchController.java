package com.intelligence.platform.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.intelligence.platform.entity.*;
import com.intelligence.platform.mapper.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/search")
@CrossOrigin(origins = "*")
public class SearchController {

    @Autowired
    private DocumentMapper documentMapper;
    @Autowired
    private ReportMapper reportMapper;
    @Autowired
    private QARecordMapper qaRecordMapper;
    @Autowired
    private AnalysisReportMapper analysisReportMapper;
    @Autowired
    private RiskAlertMapper riskAlertMapper;
    @Autowired
    private DecisionMapper decisionMapper;
    @Autowired
    private ProjectMapper projectMapper;
    @Autowired
    private KGNodeMapper kgNodeMapper;
    @Autowired
    private KGEdgeMapper kgEdgeMapper;

    @GetMapping
    public Map<String, Object> unifiedSearch(
            @RequestParam String q,
            @RequestParam(required = false) String scope,
            @RequestParam(defaultValue = "20") int limit) {

        Map<String, Object> results = new HashMap<>();
        String kw = "%" + q + "%";

        if (scope == null || "all".equals(scope) || "documents".equals(scope)) {
            List<Document> docs = documentMapper.selectList(
                    new LambdaQueryWrapper<Document>()
                            .like(Document::getTitle, q)
                            .or().like(Document::getKeywords, q)
                            .or().like(Document::getCategoryL1, q)
                            .orderByDesc(Document::getUploadTime)
                            .last("LIMIT " + limit));
            results.put("documents", docs);
        }

        if (scope == null || "all".equals(scope) || "reports".equals(scope)) {
            List<Report> reports = reportMapper.selectList(
                    new LambdaQueryWrapper<Report>()
                            .like(Report::getTitle, q)
                            .or().like(Report::getSummary, q)
                            .or().like(Report::getCategoryL1, q)
                            .orderByDesc(Report::getUploadTime)
                            .last("LIMIT " + limit));
            results.put("reports", reports);
        }

        if (scope == null || "all".equals(scope) || "qa".equals(scope)) {
            List<QARecord> qas = qaRecordMapper.selectList(
                    new LambdaQueryWrapper<QARecord>()
                            .like(QARecord::getQuestion, q)
                            .or().like(QARecord::getAnswer, q)
                            .orderByDesc(QARecord::getCreatedAt)
                            .last("LIMIT " + limit));
            results.put("qa", qas);
        }

        if (scope == null || "all".equals(scope) || "analysis".equals(scope)) {
            List<AnalysisReport> analyses = analysisReportMapper.selectList(
                    new LambdaQueryWrapper<AnalysisReport>()
                            .like(AnalysisReport::getTitle, q)
                            .or().like(AnalysisReport::getContent, q)
                            .orderByDesc(AnalysisReport::getCreatedAt)
                            .last("LIMIT " + limit));
            results.put("analysis", analyses);
        }

        int total = 0;
        for (Object v : results.values()) {
            if (v instanceof List) total += ((List<?>) v).size();
        }

        return Map.of("query", q, "total", total, "results", results);
    }

    @GetMapping("/stats/full")
    public Map<String, Object> fullStats() {
        Map<String, Object> counts = new HashMap<>();
        counts.put("documents", documentMapper.selectCount(null));
        counts.put("reports", reportMapper.selectCount(null));
        counts.put("qa_records", qaRecordMapper.selectCount(null));
        counts.put("analysis_reports", analysisReportMapper.selectCount(null));
        counts.put("risk_alerts", riskAlertMapper.selectCount(null));
        counts.put("decisions", decisionMapper.selectCount(null));
        counts.put("projects", projectMapper.selectCount(null));
        counts.put("kg_nodes", kgNodeMapper.selectCount(null));
        counts.put("kg_edges", kgEdgeMapper.selectCount(null));
        return counts;
    }
}
