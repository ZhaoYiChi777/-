package com.intelligence.platform.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.intelligence.platform.common.PageResult;
import com.intelligence.platform.entity.KGEdge;
import com.intelligence.platform.entity.KGNode;
import com.intelligence.platform.entity.KGBuildEvent;
import com.intelligence.platform.entity.KGBuildJob;
import com.intelligence.platform.entity.KGEntryBuildState;
import com.intelligence.platform.entity.KnowledgeEntry;
import com.intelligence.platform.mapper.KGBuildEventMapper;
import com.intelligence.platform.mapper.KGBuildJobMapper;
import com.intelligence.platform.mapper.KGEntryBuildStateMapper;
import com.intelligence.platform.mapper.KGEdgeMapper;
import com.intelligence.platform.mapper.KGNodeMapper;
import com.intelligence.platform.mapper.KnowledgeEntryMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class KGService {

    private static final Logger log = LoggerFactory.getLogger(KGService.class);
    private static final int SOURCE_OVERLAP_MAX_FORWARD_NEIGHBORS = 2;
    private static final DateTimeFormatter BUILD_TIME_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    @Autowired
    private com.intelligence.platform.client.KGComputeClient kgComputeClient;

    @Autowired
    private KGNodeMapper kgNodeMapper;
    @Autowired
    private KGEdgeMapper kgEdgeMapper;
    @Autowired
    private KnowledgeEntryMapper knowledgeEntryMapper;
    @Autowired
    private KGBuildJobMapper kgBuildJobMapper;
    @Autowired
    private KGEntryBuildStateMapper kgEntryBuildStateMapper;
    @Autowired
    private KGBuildEventMapper kgBuildEventMapper;
    @Autowired
    private LlmService llmService;
    @Autowired
    private ProjectContext projectContext;

    private final ObjectMapper jsonMapper = new ObjectMapper();

    // ==================== 项目级查询辅助 ====================

    List<KGNode> getProjectNodes() {
        Long pid = projectContext.getCurrentProjectId();
        if (pid == null) return Collections.emptyList();
        LambdaQueryWrapper<KGNode> w = new LambdaQueryWrapper<>();
        w.eq(KGNode::getProjectId, pid);
        return kgNodeMapper.selectList(w);
    }

    List<KGEdge> getProjectEdges() {
        Long pid = projectContext.getCurrentProjectId();
        if (pid == null) return Collections.emptyList();
        LambdaQueryWrapper<KGEdge> w = new LambdaQueryWrapper<>();
        w.eq(KGEdge::getProjectId, pid);
        return kgEdgeMapper.selectList(w);
    }

    public Map<String, Object> getLatestBuildJob() {
        Long pid = projectContext.getCurrentProjectId();
        if (pid == null) {
            return Map.of("found", false, "message", "Project ID is required.");
        }

        KGBuildJob job = kgBuildJobMapper.selectOne(new LambdaQueryWrapper<KGBuildJob>()
                .eq(KGBuildJob::getProjectId, pid)
                .orderByDesc(KGBuildJob::getId)
                .last("LIMIT 1"));
        return job == null ? Map.of("found", false) : buildJobDetails(job);
    }

    public Map<String, Object> getBuildJob(Long jobId) {
        Long pid = projectContext.getCurrentProjectId();
        if (pid == null) {
            return Map.of("found", false, "message", "Project ID is required.");
        }

        KGBuildJob job = kgBuildJobMapper.selectOne(new LambdaQueryWrapper<KGBuildJob>()
                .eq(KGBuildJob::getId, jobId)
                .eq(KGBuildJob::getProjectId, pid)
                .last("LIMIT 1"));
        return job == null ? Map.of("found", false, "jobId", jobId) : buildJobDetails(job);
    }

    // ==================== 节点分页查询 ====================

    public PageResult<Map<String, Object>> getNodes(int page, int pageSize) {
        Long pid = projectContext.getCurrentProjectId();
        if (pid == null) {
            return new PageResult<>(0, page, pageSize, Collections.emptyList());
        }

        LambdaQueryWrapper<KGNode> nodeWrapper = new LambdaQueryWrapper<>();
        nodeWrapper.eq(KGNode::getProjectId, pid);

        Page<KGNode> nodePage = kgNodeMapper.selectPage(new Page<>(page, pageSize), nodeWrapper);
        List<KGNode> nodes = nodePage.getRecords();
        List<KGNode> allNodes = getProjectNodes();
        List<KGEdge> allEdges = getProjectEdges();
        Map<Long, Integer> linkCounts = computeLinkCounts(allNodes, allEdges);
        Map<Long, Double> pageRankScores = computePageRankScores(allNodes, allEdges);
        double maxPageRank = pageRankScores.values().stream().mapToDouble(Double::doubleValue).max().orElse(0.0);

        // 获取当前项目的知识词条，用于富化 keywords/library
        LambdaQueryWrapper<KnowledgeEntry> entryWrapper = new LambdaQueryWrapper<>();
        entryWrapper.eq(KnowledgeEntry::getProjectId, pid);
        List<KnowledgeEntry> entries = knowledgeEntryMapper.selectList(entryWrapper);
        Map<String, KnowledgeEntry> titleToEntry = entries.stream()
                .collect(Collectors.toMap(KnowledgeEntry::getTitle, e -> e, (a, b) -> a));

        List<Map<String, Object>> enriched = new ArrayList<>();
        for (KGNode node : nodes) {
            Map<String, Object> m = new HashMap<>();
            m.put("id", node.getId());
            m.put("label", node.getLabel());
            m.put("nodeType", node.getNodeType());
            m.put("description", node.getDescription());
            m.put("communityId", node.getCommunityId());
            m.put("projectId", node.getProjectId());
            m.put("linkCount", linkCounts.getOrDefault(node.getId(), 0));
            double pageRank = pageRankScores.getOrDefault(node.getId(), 0.0);
            m.put("pageRank", round(pageRank, 6));
            m.put("importanceScore", maxPageRank > 0 ? round(pageRank / maxPageRank, 3) : 0.0);
            KnowledgeEntry entry = titleToEntry.get(node.getLabel());
            m.put("keywords", entry != null ? entry.getKeywords() : null);
            m.put("library", entry != null ? entry.getEntryLibrary() : null);
            m.put("entryType", entry != null ? entry.getEntryType() : null);
            enriched.add(m);
        }
        return new PageResult<>(nodePage.getTotal(), page, pageSize, enriched);
    }

    // ==================== 边分页查询 ====================

    public PageResult<KGEdge> getEdges(int page, int pageSize) {
        Long pid = projectContext.getCurrentProjectId();
        if (pid == null) {
            return new PageResult<>(0, page, pageSize, Collections.emptyList());
        }

        LambdaQueryWrapper<KGEdge> edgeWrapper = new LambdaQueryWrapper<>();
        edgeWrapper.eq(KGEdge::getProjectId, pid);

        Page<KGEdge> edgePage = kgEdgeMapper.selectPage(new Page<>(page, pageSize), edgeWrapper);
        return new PageResult<>(edgePage.getTotal(), page, pageSize, edgePage.getRecords());
    }

    // ==================== 图谱数据 ====================

    public Map<String, Object> getGraphData() {
        Long pid = projectContext.getCurrentProjectId();
        if (pid == null) {
            return Map.of("nodes", Collections.emptyList(), "edges", Collections.emptyList());
        }
        
        List<KGNode> nodes = getProjectNodes();
        List<KGEdge> edges = getProjectEdges();

        // 如果节点为空，尝试自动从知识词条构建
        if (nodes.isEmpty()) {
            buildGraph();
            nodes = getProjectNodes();
            edges = getProjectEdges();
        }

        // 获取知识词条用于富化节点信息
        LambdaQueryWrapper<KnowledgeEntry> entryWrapper = new LambdaQueryWrapper<>();
        entryWrapper.eq(KnowledgeEntry::getProjectId, pid);
        List<KnowledgeEntry> entries = knowledgeEntryMapper.selectList(entryWrapper);
        Map<String, KnowledgeEntry> titleToEntry = entries.stream()
                .collect(Collectors.toMap(KnowledgeEntry::getTitle, e -> e, (a, b) -> a));

        // 计算每个节点的连接数
        Map<Long, Integer> linkCounts = new HashMap<>();
        for (KGNode n : nodes) linkCounts.put(n.getId(), 0);
        for (KGEdge e : edges) {
            linkCounts.merge(e.getSourceId(), 1, Integer::sum);
            linkCounts.merge(e.getTargetId(), 1, Integer::sum);
        }
        Map<Long, Double> pageRankScores = computePageRankScores(nodes, edges);
        double maxPageRank = pageRankScores.values().stream().mapToDouble(Double::doubleValue).max().orElse(0.0);

        // 将 linkCount、keywords、entryType 附加到节点数据中
        List<Map<String, Object>> enrichedNodes = new ArrayList<>();
        for (KGNode node : nodes) {
            Map<String, Object> n = new HashMap<>();
            n.put("id", node.getId());
            n.put("label", node.getLabel());
            n.put("nodeType", node.getNodeType());
            n.put("description", node.getDescription());
            n.put("communityId", node.getCommunityId() != null ? node.getCommunityId() : 0);
            n.put("linkCount", linkCounts.getOrDefault(node.getId(), 0));
            double pageRank = pageRankScores.getOrDefault(node.getId(), 0.0);
            n.put("pageRank", round(pageRank, 6));
            n.put("importanceScore", maxPageRank > 0 ? round(pageRank / maxPageRank, 3) : 0.0);
            KnowledgeEntry entry = titleToEntry.get(node.getLabel());
            if (entry != null) {
                n.put("keywords", entry.getKeywords() != null
                        ? Arrays.asList(entry.getKeywords().split(",\\s*"))
                        : Collections.emptyList());
                n.put("entryType", entry.getEntryLibrary() != null ? entry.getEntryLibrary() :
                        entry.getEntryType() != null ? entry.getEntryType() : null);
            } else {
                n.put("keywords", Collections.emptyList());
                n.put("entryType", null);
            }
            enrichedNodes.add(n);
        }

        // 构建社区（含富化后的成员信息）
        Map<Integer, Map<String, Object>> communities = new LinkedHashMap<>();
        for (KGNode node : nodes) {
            int cid = node.getCommunityId() != null ? node.getCommunityId() : 0;
            communities.computeIfAbsent(cid, k -> {
                Map<String, Object> comm = new HashMap<>();
                comm.put("id", k);
                comm.put("members", new ArrayList<>());
                comm.put("cohesion", 0.0);
                return comm;
            });
            // 富化社区成员信息
            Map<String, Object> memberMap = new HashMap<>();
            memberMap.put("id", node.getId());
            memberMap.put("label", node.getLabel());
            KnowledgeEntry entry = titleToEntry.get(node.getLabel());
            if (entry != null && entry.getKeywords() != null) {
                memberMap.put("keywords", Arrays.asList(entry.getKeywords().split(",\\s*")));
            }
            ((List<Map<String, Object>>) communities.get(cid).get("members")).add(memberMap);
        }

        // 计算社区内聚度
        for (Map.Entry<Integer, Map<String, Object>> entry : communities.entrySet()) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> members = (List<Map<String, Object>>) entry.getValue().get("members");
            Set<Long> memberIds = members.stream()
                    .map(m -> ((Number) m.get("id")).longValue())
                    .collect(Collectors.toSet());
            long internalEdges = edges.stream()
                    .filter(e -> memberIds.contains(e.getSourceId()) && memberIds.contains(e.getTargetId()))
                    .count();
            int n = memberIds.size();
            double possible = n > 1 ? (double) n * (n - 1) / 2 : 1;
            entry.getValue().put("cohesion", Math.round(internalEdges / possible * 1000.0) / 1000.0);
        }

        // 边类型统计
        Map<String, Long> edgeTypes = edges.stream()
                .collect(Collectors.groupingBy(KGEdge::getEdgeType, Collectors.counting()));

        Map<String, Object> result = new HashMap<>();
        result.put("nodes", enrichedNodes);
        result.put("edges", edges);
        result.put("communities", new ArrayList<>(communities.values()));
        result.put("stats", Map.of(
                "node_count", nodes.size(),
                "edge_count", edges.size(),
                "community_count", communities.size(),
                "ranked_node_count", pageRankScores.size(),
                "edge_types", edgeTypes
        ));
        return result;
    }

    // ==================== 洞察分析 ====================

    public List<Map<String, String>> getInsights() {
        List<KGNode> nodes = getProjectNodes();
        List<KGEdge> edges = getProjectEdges();
        List<Map<String, String>> insights = new ArrayList<>();

        Map<Long, String> nodeLabel = nodes.stream().collect(Collectors.toMap(KGNode::getId, KGNode::getLabel));
        Map<Long, Integer> nodeComm = nodes.stream().collect(Collectors.toMap(KGNode::getId, n -> n.getCommunityId() != null ? n.getCommunityId() : 0));
        Map<Long, String> nodeType = nodes.stream().collect(Collectors.toMap(KGNode::getId, KGNode::getNodeType));

        // 计算度数
        Map<Long, Integer> degrees = new HashMap<>();
        for (KGNode n : nodes) degrees.put(n.getId(), 0);
        for (KGEdge e : edges) {
            degrees.merge(e.getSourceId(), 1, Integer::sum);
            degrees.merge(e.getTargetId(), 1, Integer::sum);
        }

        // 孤立节点
        for (KGNode n : nodes) {
            if (degrees.getOrDefault(n.getId(), 0) <= 1) {
                insights.add(Map.of("type", "isolated", "title", "孤立节点: " + n.getLabel(),
                        "desc", "与图谱其余部分连接薄弱"));
            }
        }

        // 惊奇连接
        for (KGEdge e : edges) {
            if (List.of("source_overlap", "adamic_adar").contains(e.getEdgeType())) {
                Long s = e.getSourceId(), t = e.getTargetId();
                if (!Objects.equals(nodeComm.get(s), nodeComm.get(t))
                        && !Objects.equals(nodeType.get(s), nodeType.get(t))) {
                    insights.add(Map.of("type", "surprise",
                            "title", "惊奇连接: " + nodeLabel.getOrDefault(s, "?") + " ↔ " + nodeLabel.getOrDefault(t, "?"),
                            "desc", "跨社区的 " + e.getEdgeType() + " 关联"));
                }
            }
        }

        return insights.subList(0, Math.min(10, insights.size()));
    }

    // ==================== 图谱构建 ====================

    public Map<String, Object> buildGraph() {
        Long projectId = projectContext.getCurrentProjectId();
        if (projectId == null) {
            return Map.of("status", "failed", "message", "Project ID is required.");
        }

        KGBuildJob job = createBuildJob(projectId, "pseudo_incremental");
        try {
            autoBuildGraph(job);
        List<KGNode> nodes = getProjectNodes();
        List<KGEdge> edges = getProjectEdges();
        return Map.of("message", "图谱构建完成",
                "node_count", nodes.size(), "edge_count", edges.size(),
                "job_id", job.getId(), "status", completeBuildJob(job, nodes.size(), edges.size()));
        } catch (Exception e) {
            failBuildJob(job, e);
            log.error("Knowledge graph build failed for project {} and job {}", projectId, job.getId(), e);
            return Map.of(
                    "job_id", job.getId(),
                    "status", job.getStatus(),
                    "message", "Knowledge graph build failed."
            );
        }
    }

    // ==================== 私有辅助方法 ====================

    private KGBuildJob createBuildJob(Long projectId, String buildMode) {
        KGBuildJob job = new KGBuildJob();
        job.setProjectId(projectId);
        job.setStatus("running");
        job.setBuildMode(buildMode);
        job.setGraphVersion(System.currentTimeMillis());
        job.setTotalEntries(0);
        job.setProcessedEntries(0);
        job.setNodeCount(0);
        job.setEdgeCount(0);
        job.setStartedAt(now());
        kgBuildJobMapper.insert(job);
        recordBuildEvent(job, "started", "Knowledge graph build started.",
                Map.of("buildMode", buildMode, "graphVersion", job.getGraphVersion()));
        return job;
    }

    private String completeBuildJob(KGBuildJob job, int nodeCount, int edgeCount) {
        job.setStatus("succeeded");
        job.setNodeCount(nodeCount);
        job.setEdgeCount(edgeCount);
        job.setProcessedEntries(job.getTotalEntries());
        job.setFinishedAt(now());
        kgBuildJobMapper.updateById(job);
        recordBuildEvent(job, "completed", "Knowledge graph build completed.",
                Map.of("nodeCount", nodeCount, "edgeCount", edgeCount));
        return job.getStatus();
    }

    private void failBuildJob(KGBuildJob job, Exception error) {
        String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        job.setStatus("failed");
        job.setErrorMessage(message);
        job.setFinishedAt(now());
        kgBuildJobMapper.updateById(job);
        recordBuildEvent(job, "failed", "Knowledge graph build failed.",
                Map.of("error", message));
    }

    private void initializeBuildProgress(KGBuildJob job, int totalEntries) {
        job.setTotalEntries(totalEntries);
        job.setProcessedEntries(0);
        job.setNodeCount(0);
        job.setEdgeCount(0);
        kgBuildJobMapper.updateById(job);
        recordBuildEvent(job, "entries_loaded", "Eligible knowledge entries were loaded.",
                Map.of("totalEntries", totalEntries));
    }

    private void updateBuildProgress(KGBuildJob job, int processedEntries, int nodeCount, int edgeCount) {
        job.setProcessedEntries(processedEntries);
        job.setNodeCount(nodeCount);
        job.setEdgeCount(edgeCount);
        kgBuildJobMapper.updateById(job);
    }

    private void clearProjectGraph(Long projectId) {
        kgEdgeMapper.delete(new LambdaQueryWrapper<KGEdge>().eq(KGEdge::getProjectId, projectId));
        kgNodeMapper.delete(new LambdaQueryWrapper<KGNode>().eq(KGNode::getProjectId, projectId));
    }

    private void cleanupStaleEntryBuildStates(Long projectId, List<KnowledgeEntry> entries) {
        Set<Long> currentEntryIds = entries.stream()
                .map(KnowledgeEntry::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        List<KGEntryBuildState> states = kgEntryBuildStateMapper.selectList(
                new LambdaQueryWrapper<KGEntryBuildState>().eq(KGEntryBuildState::getProjectId, projectId));
        for (KGEntryBuildState state : states) {
            if (state.getEntryId() == null || !currentEntryIds.contains(state.getEntryId())) {
                kgEntryBuildStateMapper.deleteById(state.getId());
            }
        }
    }

    private IncrementalScanResult scanIncrementalState(Long projectId, List<KnowledgeEntry> entries) {
        List<KGEntryBuildState> existingStates = kgEntryBuildStateMapper.selectList(
                new LambdaQueryWrapper<KGEntryBuildState>().eq(KGEntryBuildState::getProjectId, projectId));
        Map<Long, String> existingHashes = existingStates.stream()
                .filter(state -> state.getEntryId() != null)
                .collect(Collectors.toMap(
                        KGEntryBuildState::getEntryId,
                        KGEntryBuildState::getEntryHash,
                        (left, right) -> left));
        return analyzeIncrementalChanges(entries, existingHashes);
    }

    static IncrementalScanResult analyzeIncrementalChanges(
            List<KnowledgeEntry> entries,
            Map<Long, String> existingHashes) {
        int newEntries = 0;
        int changedEntries = 0;
        int unchangedEntries = 0;
        Set<Long> currentEntryIds = new HashSet<>();

        for (KnowledgeEntry entry : entries) {
            if (entry.getId() == null) {
                newEntries++;
                continue;
            }

            currentEntryIds.add(entry.getId());
            String existingHash = existingHashes.get(entry.getId());
            String currentHash = buildEntryHash(entry);
            if (existingHash == null) {
                newEntries++;
            } else if (!existingHash.equals(currentHash)) {
                changedEntries++;
            } else {
                unchangedEntries++;
            }
        }

        int deletedEntries = 0;
        for (Long existingEntryId : existingHashes.keySet()) {
            if (!currentEntryIds.contains(existingEntryId)) {
                deletedEntries++;
            }
        }

        return new IncrementalScanResult(
                entries.size(),
                newEntries,
                changedEntries,
                unchangedEntries,
                deletedEntries);
    }

    private void upsertEntryBuildState(KnowledgeEntry entry, KGNode node, KGBuildJob job) {
        if (entry.getId() == null) {
            return;
        }

        KGEntryBuildState state = kgEntryBuildStateMapper.selectOne(
                new LambdaQueryWrapper<KGEntryBuildState>()
                        .eq(KGEntryBuildState::getProjectId, job.getProjectId())
                        .eq(KGEntryBuildState::getEntryId, entry.getId())
                        .last("LIMIT 1"));
        if (state == null) {
            state = new KGEntryBuildState();
            state.setProjectId(job.getProjectId());
            state.setEntryId(entry.getId());
        }

        state.setEntryHash(buildEntryHash(entry));
        state.setGraphVersion(job.getGraphVersion());
        state.setNodeId(node.getId());
        state.setStatus("clean");
        state.setLastBuiltAt(now());
        if (state.getId() == null) {
            kgEntryBuildStateMapper.insert(state);
        } else {
            kgEntryBuildStateMapper.updateById(state);
        }
    }

    static String buildEntryHash(KnowledgeEntry entry) {
        String material = String.join("\u001F",
                safeHashValue(entry.getTitle()),
                safeHashValue(entry.getEntryType()),
                safeHashValue(entry.getEntryLibrary()),
                safeHashValue(entry.getDocumentId()),
                safeHashValue(entry.getSourceName()),
                safeHashValue(entry.getSourceOrigin()),
                safeHashValue(entry.getContent()),
                safeHashValue(entry.getKeywords()),
                safeHashValue(entry.getCategoryL1()),
                safeHashValue(entry.getCategoryL2()),
                safeHashValue(entry.getDescription()),
                safeHashValue(entry.getRelated()));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8));
            StringBuilder hash = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                hash.append(String.format("%02x", value));
            }
            return hash.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable.", e);
        }
    }

    private static String safeHashValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private Map<String, Object> buildJobDetails(KGBuildJob job) {
        List<KGBuildEvent> events = kgBuildEventMapper.selectList(
                new LambdaQueryWrapper<KGBuildEvent>()
                        .eq(KGBuildEvent::getJobId, job.getId())
                        .orderByAsc(KGBuildEvent::getId));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("found", true);
        result.put("job_id", job.getId());
        result.put("status", job.getStatus());
        result.put("job", job);
        result.put("events", events);
        return result;
    }

    private void recordBuildEvent(KGBuildJob job, String eventType, String message) {
        recordBuildEvent(job, eventType, message, Collections.emptyMap());
    }

    private void recordBuildEvent(
            KGBuildJob job,
            String eventType,
            String message,
            Map<String, Object> payload) {
        try {
            KGBuildEvent event = new KGBuildEvent();
            event.setJobId(job.getId());
            event.setProjectId(job.getProjectId());
            event.setEventType(eventType);
            event.setMessage(message);
            event.setPayloadJson(jsonMapper.writeValueAsString(payload));
            event.setCreatedAt(now());
            kgBuildEventMapper.insert(event);
        } catch (Exception e) {
            log.warn("Could not record graph build event {} for job {}: {}",
                    eventType, job.getId(), e.getMessage());
        }
    }

    private String now() {
        return LocalDateTime.now().format(BUILD_TIME_FORMAT);
    }

    private Map<Long, Integer> computeLinkCounts(List<KGNode> nodes, List<KGEdge> edges) {
        Map<Long, Integer> linkCounts = new HashMap<>();
        for (KGNode node : nodes) {
            linkCounts.put(node.getId(), 0);
        }
        for (KGEdge edge : edges) {
            linkCounts.merge(edge.getSourceId(), 1, Integer::sum);
            linkCounts.merge(edge.getTargetId(), 1, Integer::sum);
        }
        return linkCounts;
    }

    private Map<Long, Double> computePageRankScores(List<KGNode> nodes, List<KGEdge> edges) {
        if (nodes.isEmpty()) {
            return Collections.emptyMap();
        }

        try {
            List<Map<String, Object>> nodeDataList = new ArrayList<>();
            for (KGNode node : nodes) {
                Map<String, Object> nd = new HashMap<>();
                nd.put("id", String.valueOf(node.getId()));
                nd.put("label", node.getLabel());
                nodeDataList.add(nd);
            }

            List<Map<String, Object>> edgeDataList = new ArrayList<>();
            for (KGEdge edge : edges) {
                Map<String, Object> ed = new HashMap<>();
                ed.put("source", String.valueOf(edge.getSourceId()));
                ed.put("target", String.valueOf(edge.getTargetId()));
                ed.put("weight", edge.getWeight() != null ? edge.getWeight() : 1.0);
                edgeDataList.add(ed);
            }

            Map<String, Object> graphInput = new HashMap<>();
            graphInput.put("algorithm", "pagerank");
            graphInput.put("nodes", nodeDataList);
            graphInput.put("edges", edgeDataList);

            String jsonOutput = kgComputeClient.computePagerank(jsonMapper.writeValueAsString(graphInput));
            com.fasterxml.jackson.databind.JsonNode root = jsonMapper.readTree(jsonOutput);
            Map<Long, Double> scores = new HashMap<>();
            if (root.has("nodes")) {
                for (com.fasterxml.jackson.databind.JsonNode item : root.get("nodes")) {
                    if (!item.has("id") || !item.has("score")) {
                        continue;
                    }
                    scores.put(Long.parseLong(item.get("id").asText()), item.get("score").asDouble(0.0));
                }
            }

            if (!scores.isEmpty()) {
                return scores;
            }
        } catch (Exception e) {
            log.warn("Sidecar PageRank 计算失败，回退到度中心性: {}", e.getMessage());
        }

        return computeDegreeScoreFallback(nodes, edges);
    }

    private Map<Long, Double> computeDegreeScoreFallback(List<KGNode> nodes, List<KGEdge> edges) {
        Map<Long, Integer> linkCounts = computeLinkCounts(nodes, edges);
        int totalLinks = linkCounts.values().stream().mapToInt(Integer::intValue).sum();
        Map<Long, Double> scores = new HashMap<>();

        if (totalLinks == 0) {
            double equalScore = 1.0 / nodes.size();
            for (KGNode node : nodes) {
                scores.put(node.getId(), equalScore);
            }
            return scores;
        }

        for (KGNode node : nodes) {
            scores.put(node.getId(), linkCounts.getOrDefault(node.getId(), 0) / (double) totalLinks);
        }
        return scores;
    }

    private double round(double value, int scale) {
        double factor = Math.pow(10, scale);
        return Math.round(value * factor) / factor;
    }

    /**
     * 自动从知识词条构建 KG（内部方法）
     * 不需要 LLM：直接从词条标题/关键词创建节点，基于共享关键词创建边
     */
    private synchronized List<KGNode> autoBuildGraph(KGBuildJob job) {
        Long pid = projectContext.getCurrentProjectId();
        if (pid == null) {
            log.warn("Project ID is null, skipping graph generation to prevent cross-project leakage.");
            return Collections.emptyList();
        }

        // 获取当前项目的知识词条 (排除图片和表格类条目)
        LambdaQueryWrapper<KnowledgeEntry> entryWrapper = new LambdaQueryWrapper<>();
        entryWrapper.eq(KnowledgeEntry::getProjectId, pid);
        entryWrapper.ne(KnowledgeEntry::getEntryType, "image").ne(KnowledgeEntry::getEntryType, "table");
        List<KnowledgeEntry> entries = knowledgeEntryMapper.selectList(entryWrapper);
        initializeBuildProgress(job, entries.size());
        IncrementalScanResult scanResult = scanIncrementalState(pid, entries);
        recordBuildEvent(job, "incremental_scan", "Entry hashes were compared before graph rebuild.",
                scanResult.toPayload());
        cleanupStaleEntryBuildStates(pid, entries);
        clearProjectGraph(pid);

        if (entries.isEmpty()) {
            recordBuildEvent(job, "graph_cleared", "No eligible entries were found; cleared the project graph.");
            return Collections.emptyList();
        }

        // 清理旧 of KG 数据（当前项目）

        // 1. 为每个词条创建节点
        List<KGNode> nodes = new ArrayList<>();
        Map<String, KGNode> titleToNode = new HashMap<>();
        int communityCounter = 0;
        int processedEntries = 0;

        for (KnowledgeEntry entry : entries) {
            KGNode node = new KGNode();
            node.setLabel(entry.getTitle());
            node.setNodeType(entry.getEntryType() != null ? entry.getEntryType() : "concept");
            node.setDescription(entry.getContent() != null && entry.getContent().length() > 200
                    ? entry.getContent().substring(0, 200) + "..." : entry.getContent());
            node.setCommunityId(communityCounter % 3); // 简单分组
            node.setProjectId(pid);
            kgNodeMapper.insert(node);
            nodes.add(node);
            titleToNode.put(entry.getTitle(), node);
            communityCounter++;
            upsertEntryBuildState(entry, node, job);
            updateBuildProgress(job, ++processedEntries, nodes.size(), 0);
        }

        // 2. 基于共享关键词创建边
        List<KGEdge> allEdges = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            Set<String> kw1 = parseKeywords(entries.get(i).getKeywords());
            for (int j = i + 1; j < entries.size(); j++) {
                Set<String> kw2 = parseKeywords(entries.get(j).getKeywords());
                // 计算关键词交集
                Set<String> shared = new HashSet<>(kw1);
                shared.retainAll(kw2);
                if (!shared.isEmpty()) {
                    KGEdge edge = new KGEdge();
                    edge.setSourceId(nodes.get(i).getId());
                    edge.setTargetId(nodes.get(j).getId());
                    edge.setEdgeType("keyword_overlap");
                    edge.setWeight((double) shared.size());
                    edge.setProjectId(pid);
                    allEdges.add(edge);
                }
            }
        }

        // 2.1 基于 wiki [[wikilink]] 语法生成 direct_link 边
        Map<String, Long> titleToNodeId = new HashMap<>();
        for (KGNode node : nodes) {
            if (node.getLabel() != null) {
                titleToNodeId.put(node.getLabel(), node.getId());
            }
        }
        for (int i = 0; i < entries.size(); i++) {
            KnowledgeEntry entry = entries.get(i);
            KGNode sourceNode = nodes.get(i);
            Set<String> rawLinks = extractWikilinks(entry.getContent());
            for (String linkTarget : rawLinks) {
                Long resolvedId = resolveLinkTarget(linkTarget, titleToNodeId);
                if (resolvedId != null && !resolvedId.equals(sourceNode.getId())) {
                    KGEdge edge = new KGEdge();
                    edge.setSourceId(sourceNode.getId());
                    edge.setTargetId(resolvedId);
                    edge.setEdgeType("direct_link");
                    edge.setWeight(1.0);
                    edge.setProjectId(pid);
                    allEdges.add(edge);
                }
            }
        }

        // Keep shared-source edges sparse instead of creating a clique per document.
        allEdges.addAll(buildSourceOverlapEdges(entries, nodes, pid));

        List<KGEdge> sourceContainmentEdges = buildAndInsertSourceNodes(entries, nodes, pid);
        allEdges.addAll(sourceContainmentEdges);

        batchInsertEdges(allEdges);
        updateBuildProgress(job, processedEntries, nodes.size(), allEdges.size());
        recordBuildEvent(job, "edges_built", "Rule-based graph edges were created.",
                Map.of("nodeCount", nodes.size(), "edgeCount", allEdges.size()));

        // 3. 调用 Sidecar 计算真实的社区划分 (Louvain / Union-Find)
        try {
            List<Map<String, Object>> nodeDataList = new ArrayList<>();
            for (KGNode node : nodes) {
                Map<String, Object> nd = new HashMap<>();
                nd.put("id", String.valueOf(node.getId()));
                nd.put("label", node.getLabel());
                nodeDataList.add(nd);
            }
            List<Map<String, Object>> edgeDataList = new ArrayList<>();
            for (KGEdge edge : allEdges) {
                Map<String, Object> ed = new HashMap<>();
                ed.put("source", String.valueOf(edge.getSourceId()));
                ed.put("target", String.valueOf(edge.getTargetId()));
                ed.put("weight", edge.getWeight() != null ? edge.getWeight() : 1.0);
                edgeDataList.add(ed);
            }
            Map<String, Object> graphInput = new HashMap<>();
            graphInput.put("algorithm", "louvain");
            graphInput.put("nodes", nodeDataList);
            graphInput.put("edges", edgeDataList);
            
            String jsonInput = jsonMapper.writeValueAsString(graphInput);
            String jsonOutput = kgComputeClient.computeCommunities(jsonInput);
            
            com.fasterxml.jackson.databind.JsonNode root = jsonMapper.readTree(jsonOutput);
            if (root.has("communities")) {
                com.fasterxml.jackson.databind.JsonNode comms = root.get("communities");
                for (int cIdx = 0; cIdx < comms.size(); cIdx++) {
                    com.fasterxml.jackson.databind.JsonNode comm = comms.get(cIdx);
                    for (int nIdx = 0; nIdx < comm.size(); nIdx++) {
                        long nodeId = Long.parseLong(comm.get(nIdx).asText());
                        for (KGNode node : nodes) {
                            if (node.getId() == nodeId) {
                                node.setCommunityId(cIdx);
                                kgNodeMapper.updateById(node);
                                break;
                            }
                        }
                    }
                }
                log.info("Sidecar 社区划分计算完成，包含 {} 个社区", comms.size());
            }
        } catch (Exception e) {
            log.warn("Sidecar 计算社区失败，回退到简单分组: {}", e.getMessage());
            for (int k = 0; k < nodes.size(); k++) {
                KGNode node = nodes.get(k);
                node.setCommunityId(k % 3);
                kgNodeMapper.updateById(node);
            }
        }

        recordBuildEvent(job, "communities_computed", "Community assignment completed.");
        return nodes;
    }

    private List<KGEdge> buildAndInsertSourceNodes(
            List<KnowledgeEntry> entries,
            List<KGNode> nodes,
            Long projectId) {
        Map<String, KGNode> sourceNodesByKey = new LinkedHashMap<>();
        List<KGEdge> edges = new ArrayList<>();

        for (SourceMembership membership : buildSourceMemberships(entries, nodes)) {
            KGNode sourceNode = sourceNodesByKey.get(membership.sourceKey());
            if (sourceNode == null) {
                sourceNode = new KGNode();
                sourceNode.setLabel(membership.sourceLabel());
                sourceNode.setNodeType("source");
                sourceNode.setDescription("Source document: " + membership.sourceLabel());
                sourceNode.setCommunityId(0);
                sourceNode.setProjectId(projectId);
                kgNodeMapper.insert(sourceNode);
                nodes.add(sourceNode);
                sourceNodesByKey.put(membership.sourceKey(), sourceNode);
            }

            KGEdge edge = new KGEdge();
            edge.setSourceId(sourceNode.getId());
            edge.setTargetId(membership.entryNodeId());
            edge.setEdgeType("source_contains");
            edge.setWeight(1.0);
            edge.setProjectId(projectId);
            edges.add(edge);
        }

        return edges;
    }

    static List<SourceMembership> buildSourceMemberships(
            List<KnowledgeEntry> entries,
            List<KGNode> entryNodes) {
        List<SourceMembership> memberships = new ArrayList<>();
        int size = Math.min(entries.size(), entryNodes.size());
        for (int index = 0; index < size; index++) {
            KGNode entryNode = entryNodes.get(index);
            if (entryNode.getId() == null) {
                continue;
            }

            SourceIdentity identity = primarySourceIdentity(entries.get(index));
            if (identity != null) {
                memberships.add(new SourceMembership(
                        identity.key(),
                        identity.label(),
                        entryNode.getId()
                ));
            }
        }
        return memberships;
    }

    static List<KGEdge> buildSourceOverlapEdges(
            List<KnowledgeEntry> entries,
            List<KGNode> nodes,
            Long projectId) {
        Map<String, List<Integer>> sourceGroups = new LinkedHashMap<>();
        for (int index = 0; index < entries.size(); index++) {
            for (String sourceKey : sourceIdentityKeys(entries.get(index))) {
                sourceGroups.computeIfAbsent(sourceKey, key -> new ArrayList<>()).add(index);
            }
        }

        Map<SourcePair, Integer> pairSignalCounts = new LinkedHashMap<>();
        for (List<Integer> rawMembers : sourceGroups.values()) {
            List<Integer> members = new ArrayList<>(new LinkedHashSet<>(rawMembers));
            members.sort((left, right) -> compareNodeIndexes(nodes, left, right));

            for (int i = 0; i < members.size(); i++) {
                for (int offset = 1;
                     offset <= SOURCE_OVERLAP_MAX_FORWARD_NEIGHBORS && i + offset < members.size();
                     offset++) {
                    KGNode first = nodes.get(members.get(i));
                    KGNode second = nodes.get(members.get(i + offset));
                    if (first.getId() == null || second.getId() == null
                            || Objects.equals(first.getId(), second.getId())) {
                        continue;
                    }

                    SourcePair pair = first.getId() < second.getId()
                            ? new SourcePair(first.getId(), second.getId())
                            : new SourcePair(second.getId(), first.getId());
                    pairSignalCounts.merge(pair, 1, Integer::sum);
                }
            }
        }

        List<KGEdge> result = new ArrayList<>(pairSignalCounts.size());
        for (Map.Entry<SourcePair, Integer> pairEntry : pairSignalCounts.entrySet()) {
            KGEdge edge = new KGEdge();
            edge.setSourceId(pairEntry.getKey().sourceId());
            edge.setTargetId(pairEntry.getKey().targetId());
            edge.setEdgeType("source_overlap");
            edge.setWeight(pairEntry.getValue().doubleValue());
            edge.setProjectId(projectId);
            result.add(edge);
        }
        return result;
    }

    private static SourceIdentity primarySourceIdentity(KnowledgeEntry entry) {
        String sourceName = normalizeSourceName(entry.getSourceName());
        if (entry.getDocumentId() != null) {
            String label = !sourceName.isEmpty() ? entry.getSourceName().trim() : "Document " + entry.getDocumentId();
            return new SourceIdentity("document:" + entry.getDocumentId(), label);
        }

        if (!sourceName.isEmpty()) {
            return new SourceIdentity("source:" + sourceName, entry.getSourceName().trim());
        }
        return null;
    }

    private static Set<String> sourceIdentityKeys(KnowledgeEntry entry) {
        Set<String> keys = new LinkedHashSet<>();
        if (entry.getDocumentId() != null) {
            keys.add("document:" + entry.getDocumentId());
        }

        String sourceName = normalizeSourceName(entry.getSourceName());
        if (!sourceName.isEmpty()) {
            keys.add("source:" + sourceName);
        }
        return keys;
    }

    private static String normalizeSourceName(String sourceName) {
        return sourceName == null
                ? ""
                : sourceName.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private static int compareNodeIndexes(List<KGNode> nodes, int left, int right) {
        Long leftId = nodes.get(left).getId();
        Long rightId = nodes.get(right).getId();
        if (leftId == null && rightId == null) {
            return Integer.compare(left, right);
        }
        if (leftId == null) {
            return 1;
        }
        if (rightId == null) {
            return -1;
        }
        int idComparison = leftId.compareTo(rightId);
        return idComparison != 0 ? idComparison : Integer.compare(left, right);
    }

    private record SourcePair(Long sourceId, Long targetId) {
    }

    record IncrementalScanResult(
            int totalEntries,
            int newEntries,
            int changedEntries,
            int unchangedEntries,
            int deletedEntries) {
        int dirtyEntries() {
            return newEntries + changedEntries + deletedEntries;
        }

        Map<String, Object> toPayload() {
            return Map.of(
                    "totalEntries", totalEntries,
                    "newEntries", newEntries,
                    "changedEntries", changedEntries,
                    "unchangedEntries", unchangedEntries,
                    "deletedEntries", deletedEntries,
                    "dirtyEntries", dirtyEntries());
        }
    }

    record SourceIdentity(String key, String label) {
    }

    record SourceMembership(String sourceKey, String sourceLabel, Long entryNodeId) {
    }

    private Set<String> extractWikilinks(String content) {
        if (content == null || content.isEmpty()) {
            return Collections.emptySet();
        }
        Set<String> links = new HashSet<>();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\[\\[([^\\]|]+?)(?:\\|[^\\]]+?)?\\]\\]").matcher(content);
        while (m.find()) {
            links.add(m.group(1).trim());
        }
        return links;
    }

    private Long resolveLinkTarget(String link, Map<String, Long> titleToIdMap) {
        if (titleToIdMap.containsKey(link)) return titleToIdMap.get(link);
        String normalized = link.toLowerCase().replaceAll("\\s+", "-");
        for (Map.Entry<String, Long> entry : titleToIdMap.entrySet()) {
            String keyLower = entry.getKey().toLowerCase();
            if (keyLower.equals(link.toLowerCase()) || keyLower.replaceAll("\\s+", "-").equals(normalized)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private Set<String> parseKeywords(String keywords) {
        if (keywords == null || keywords.isEmpty()) return Collections.emptySet();
        return Arrays.stream(keywords.split("[,，、;；\\s]+"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
    }

    private void batchInsertEdges(List<KGEdge> edges) {
        if (edges.isEmpty()) return;
        int batchSize = 100;
        for (int i = 0; i < edges.size(); i += batchSize) {
            int end = Math.min(i + batchSize, edges.size());
            List<KGEdge> batch = edges.subList(i, end);
            kgEdgeMapper.insert(batch);
        }
    }
}
