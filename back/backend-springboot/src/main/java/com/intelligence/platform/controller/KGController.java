package com.intelligence.platform.controller;

import com.intelligence.platform.common.PageResult;
import com.intelligence.platform.entity.KGEdge;
import com.intelligence.platform.entity.KGRelationCandidate;
import com.intelligence.platform.service.KGService;
import com.intelligence.platform.service.SemanticRelationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/kg")
@CrossOrigin(origins = "*")
public class KGController {

    @Autowired
    private KGService kgService;
    @Autowired
    private SemanticRelationService semanticRelationService;

    @GetMapping("/nodes")
    public PageResult<Map<String, Object>> getNodes(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int pageSize) {
        return kgService.getNodes(page, pageSize);
    }

    @GetMapping("/edges")
    public PageResult<KGEdge> getEdges(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int pageSize) {
        return kgService.getEdges(page, pageSize);
    }

    @GetMapping("/graph")
    public Map<String, Object> getGraph() {
        return kgService.getGraphData();
    }

    @GetMapping("/insights")
    public List<Map<String, String>> getInsights() {
        return kgService.getInsights();
    }

    @GetMapping("/build-jobs/latest")
    public Map<String, Object> getLatestBuildJob() {
        return kgService.getLatestBuildJob();
    }

    @GetMapping("/build-jobs/{jobId}")
    public Map<String, Object> getBuildJob(@PathVariable Long jobId) {
        return kgService.getBuildJob(jobId);
    }

    @PostMapping("/build")
    public Map<String, Object> buildGraph() {
        return kgService.buildGraph();
    }

    @PostMapping("/semantic-relations/extract")
    public Map<String, Object> extractSemanticRelations(
            @RequestParam(defaultValue = "false") boolean includeClean,
            @RequestParam(required = false) Integer limit) {
        return semanticRelationService.extractSemanticRelations(includeClean, limit);
    }

    @GetMapping("/semantic-relations/candidates")
    public PageResult<KGRelationCandidate> getSemanticRelationCandidates(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int pageSize) {
        return semanticRelationService.getCandidates(status, page, pageSize);
    }
}
