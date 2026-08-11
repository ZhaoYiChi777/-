package com.intelligence.platform.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelligence.platform.common.PageResult;
import com.intelligence.platform.entity.KGEdge;
import com.intelligence.platform.entity.KGEntryBuildState;
import com.intelligence.platform.entity.KGNode;
import com.intelligence.platform.entity.KGRelationCandidate;
import com.intelligence.platform.entity.KnowledgeEntry;
import com.intelligence.platform.entity.LlmConfig;
import com.intelligence.platform.mapper.KGEdgeMapper;
import com.intelligence.platform.mapper.KGEntryBuildStateMapper;
import com.intelligence.platform.mapper.KGNodeMapper;
import com.intelligence.platform.mapper.KGRelationCandidateMapper;
import com.intelligence.platform.mapper.KnowledgeEntryMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class SemanticRelationService {

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
    private static final Set<String> ALLOWED_RELATION_TYPES = Set.of(
            "defines",
            "belongs_to",
            "references",
            "causes",
            "parent_of",
            "child_of",
            "synonym_of",
            "applies_when",
            "depends_on",
            "conflicts_with"
    );
    private static final Set<String> AUTO_ACCEPT_RELATION_TYPES = Set.of(
            "defines",
            "belongs_to",
            "references",
            "synonym_of"
    );

    @Autowired
    private ProjectContext projectContext;
    @Autowired
    private KnowledgeEntryMapper knowledgeEntryMapper;
    @Autowired
    private KGEntryBuildStateMapper kgEntryBuildStateMapper;
    @Autowired
    private KGRelationCandidateMapper kgRelationCandidateMapper;
    @Autowired
    private KGNodeMapper kgNodeMapper;
    @Autowired
    private KGEdgeMapper kgEdgeMapper;
    @Autowired
    private LlmService llmService;

    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${semantic.extract.enabled:false}")
    private boolean semanticExtractEnabled;
    @Value("${semantic.extract.auto-promote:false}")
    private boolean semanticAutoPromote;
    @Value("${semantic.extract.reject-threshold:0.65}")
    private double rejectThreshold;
    @Value("${semantic.extract.auto-accept-threshold:0.82}")
    private double autoAcceptThreshold;
    @Value("${semantic.extract.max-dirty-entries-per-job:20}")
    private int maxDirtyEntriesPerJob;
    @Value("${semantic.extract.max-targets-per-entry:12}")
    private int maxTargetsPerEntry;
    @Value("${semantic.extract.max-content-chars:3000}")
    private int maxContentChars;

    public Map<String, Object> extractSemanticRelations(boolean includeClean, Integer limitOverride) {
        Long projectId = projectContext.getCurrentProjectId();
        if (projectId == null) {
            return Map.of("status", "failed", "message", "Project ID is required.");
        }
        if (!semanticExtractEnabled) {
            return Map.of(
                    "status", "skipped",
                    "reason", "semantic.extract.enabled=false",
                    "projectId", projectId);
        }

        LlmConfig config = llmService.getActiveExtractConfig();
        if (config == null) {
            return Map.of(
                    "status", "skipped",
                    "reason", "No extract LLM is configured.",
                    "projectId", projectId);
        }

        List<KnowledgeEntry> entries = loadTextEntries(projectId);
        if (entries.isEmpty()) {
            return Map.of("status", "succeeded", "projectId", projectId, "processedEntries", 0);
        }

        Map<Long, String> knownHashes = loadEntryHashes(projectId);
        List<KnowledgeEntry> sourceEntries = selectEntriesForExtraction(entries, knownHashes, includeClean);
        int limit = Math.max(1, limitOverride != null ? limitOverride : maxDirtyEntriesPerJob);
        sourceEntries = sourceEntries.stream().limit(limit).toList();

        Map<String, KnowledgeEntry> titleIndex = buildTitleIndex(entries);
        Map<Long, KGNode> nodeByEntryId = loadNodeByEntryId(projectId, entries);
        ExtractionSummary summary = new ExtractionSummary(projectId, sourceEntries.size());

        for (KnowledgeEntry sourceEntry : sourceEntries) {
            List<KnowledgeEntry> targets = selectTargetCandidates(sourceEntry, entries);
            if (targets.isEmpty()) {
                summary.entriesWithoutTargets++;
                continue;
            }
            try {
                String response = llmService.chat(config, buildSystemPrompt(), buildUserPrompt(sourceEntry, targets));
                List<RawRelation> rawRelations = parseRelations(response);
                summary.rawRelations += rawRelations.size();
                for (RawRelation rawRelation : rawRelations) {
                    ValidatedRelation validated = validateRelation(rawRelation, sourceEntry, titleIndex);
                    KGRelationCandidate candidate = saveCandidate(projectId, validated);
                    summary.savedCandidates++;
                    summary.incrementStatus(candidate.getStatus());
                    if (semanticAutoPromote && "accepted".equals(candidate.getStatus())) {
                        if (promoteCandidate(candidate, nodeByEntryId)) {
                            summary.promotedEdges++;
                        }
                    }
                }
                summary.processedEntries++;
            } catch (Exception e) {
                summary.failedEntries++;
                saveFailureCandidate(projectId, sourceEntry, e.getMessage());
            }
        }

        return summary.toMap();
    }

    public PageResult<KGRelationCandidate> getCandidates(String status, int page, int pageSize) {
        Long projectId = projectContext.getCurrentProjectId();
        if (projectId == null) {
            return new PageResult<>(0, page, pageSize, List.of());
        }

        LambdaQueryWrapper<KGRelationCandidate> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(KGRelationCandidate::getProjectId, projectId);
        if (status != null && !status.isBlank()) {
            wrapper.eq(KGRelationCandidate::getStatus, status);
        }
        wrapper.orderByDesc(KGRelationCandidate::getId);
        Page<KGRelationCandidate> result = kgRelationCandidateMapper.selectPage(new Page<>(page, pageSize), wrapper);
        return new PageResult<>(result.getTotal(), page, pageSize, result.getRecords());
    }

    private List<KnowledgeEntry> loadTextEntries(Long projectId) {
        return knowledgeEntryMapper.selectList(new LambdaQueryWrapper<KnowledgeEntry>()
                .eq(KnowledgeEntry::getProjectId, projectId)
                .ne(KnowledgeEntry::getEntryType, "image")
                .ne(KnowledgeEntry::getEntryType, "table"));
    }

    private Map<Long, String> loadEntryHashes(Long projectId) {
        return kgEntryBuildStateMapper.selectList(new LambdaQueryWrapper<KGEntryBuildState>()
                        .eq(KGEntryBuildState::getProjectId, projectId))
                .stream()
                .filter(state -> state.getEntryId() != null)
                .collect(Collectors.toMap(
                        KGEntryBuildState::getEntryId,
                        KGEntryBuildState::getEntryHash,
                        (left, right) -> left));
    }

    private List<KnowledgeEntry> selectEntriesForExtraction(
            List<KnowledgeEntry> entries,
            Map<Long, String> knownHashes,
            boolean includeClean) {
        if (includeClean) {
            return entries;
        }
        return entries.stream()
                .filter(entry -> entry.getId() == null
                        || !Objects.equals(knownHashes.get(entry.getId()), KGService.buildEntryHash(entry)))
                .toList();
    }

    private Map<String, KnowledgeEntry> buildTitleIndex(List<KnowledgeEntry> entries) {
        Map<String, KnowledgeEntry> titleIndex = new LinkedHashMap<>();
        for (KnowledgeEntry entry : entries) {
            if (entry.getTitle() != null && !entry.getTitle().isBlank()) {
                titleIndex.putIfAbsent(normalizeTitle(entry.getTitle()), entry);
            }
        }
        return titleIndex;
    }

    private Map<Long, KGNode> loadNodeByEntryId(Long projectId, List<KnowledgeEntry> entries) {
        Map<String, KnowledgeEntry> titleIndex = buildTitleIndex(entries);
        Map<Long, KGNode> result = new HashMap<>();
        List<KGNode> nodes = kgNodeMapper.selectList(new LambdaQueryWrapper<KGNode>()
                .eq(KGNode::getProjectId, projectId));
        for (KGNode node : nodes) {
            KnowledgeEntry entry = titleIndex.get(normalizeTitle(node.getLabel()));
            if (entry != null) {
                result.put(entry.getId(), node);
            }
        }
        return result;
    }

    private List<KnowledgeEntry> selectTargetCandidates(KnowledgeEntry sourceEntry, List<KnowledgeEntry> entries) {
        Set<String> sourceKeywords = parseKeywords(sourceEntry.getKeywords());
        List<ScoredEntry> scored = new ArrayList<>();
        for (KnowledgeEntry target : entries) {
            if (Objects.equals(sourceEntry.getId(), target.getId())) {
                continue;
            }
            double score = 0;
            if (sourceEntry.getDocumentId() != null && Objects.equals(sourceEntry.getDocumentId(), target.getDocumentId())) {
                score += 4;
            }
            if (sourceEntry.getSourceName() != null && target.getSourceName() != null
                    && normalizeText(sourceEntry.getSourceName()).equals(normalizeText(target.getSourceName()))) {
                score += 2;
            }
            Set<String> sharedKeywords = new HashSet<>(sourceKeywords);
            sharedKeywords.retainAll(parseKeywords(target.getKeywords()));
            score += sharedKeywords.size();
            if (score > 0) {
                scored.add(new ScoredEntry(target, score));
            }
        }
        return scored.stream()
                .sorted(Comparator.comparingDouble(ScoredEntry::score).reversed()
                        .thenComparing(item -> item.entry().getTitle(), Comparator.nullsLast(String::compareTo)))
                .limit(Math.max(1, maxTargetsPerEntry))
                .map(ScoredEntry::entry)
                .toList();
    }

    private String buildSystemPrompt() {
        return """
                You extract only evidence-supported semantic relations for a knowledge graph.
                Return strict JSON only, no markdown, no commentary.
                Output schema: {"relations":[{"sourceTitle":"","targetTitle":"","relationType":"","evidence":"","confidence":0.0,"reason":""}]}
                Allowed relationType values:
                defines, belongs_to, references, causes, parent_of, child_of, synonym_of, applies_when, depends_on, conflicts_with.
                Direction contracts:
                causes: source is the cause, target is the result.
                belongs_to: source is a member/instance/sub-concept, target is the category/system.
                parent_of: source is broader, target is narrower.
                child_of: source is narrower, target is broader.
                synonym_of: source and target are equivalent or near-equivalent.
                Rules:
                Only extract relations explicitly supported by the evidence text.
                Evidence must be a continuous quote copied from the supplied source or target text.
                The sourceTitle must equal the supplied SOURCE title.
                The targetTitle must be one of the supplied TARGET titles.
                If unsure, return {"relations":[]}.
                """;
    }

    private String buildUserPrompt(KnowledgeEntry sourceEntry, List<KnowledgeEntry> targets) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("SOURCE:\n");
        prompt.append("title: ").append(sourceEntry.getTitle()).append('\n');
        prompt.append("type: ").append(sourceEntry.getEntryType()).append('\n');
        prompt.append("keywords: ").append(sourceEntry.getKeywords()).append('\n');
        prompt.append("content:\n").append(truncate(sourceEntry.getContent())).append("\n\n");
        prompt.append("TARGETS:\n");
        for (int i = 0; i < targets.size(); i++) {
            KnowledgeEntry target = targets.get(i);
            prompt.append(i + 1).append(". title: ").append(target.getTitle()).append('\n');
            prompt.append("type: ").append(target.getEntryType()).append('\n');
            prompt.append("keywords: ").append(target.getKeywords()).append('\n');
            prompt.append("content:\n").append(truncate(target.getContent())).append("\n\n");
        }
        return prompt.toString();
    }

    private String truncate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= maxContentChars ? text : text.substring(0, maxContentChars);
    }

    List<RawRelation> parseRelations(String response) throws Exception {
        JsonNode root = mapper.readTree(extractJson(response));
        JsonNode relationNode = root.has("relations") ? root.get("relations") : root;
        if (!relationNode.isArray()) {
            return List.of();
        }
        List<RawRelation> relations = new ArrayList<>();
        for (JsonNode item : relationNode) {
            relations.add(new RawRelation(
                    text(item, "sourceTitle"),
                    text(item, "targetTitle"),
                    text(item, "relationType"),
                    text(item, "evidence"),
                    item.has("confidence") ? item.get("confidence").asDouble(0.0) : 0.0,
                    text(item, "reason")));
        }
        return relations;
    }

    private static String extractJson(String response) {
        if (response == null) {
            return "{\"relations\":[]}";
        }
        String trimmed = response.trim();
        if (trimmed.startsWith("```")) {
            trimmed = trimmed.replaceFirst("^```(?:json)?", "").replaceFirst("```$", "").trim();
        }
        int objectStart = trimmed.indexOf('{');
        int arrayStart = trimmed.indexOf('[');
        if (objectStart < 0 && arrayStart < 0) {
            return "{\"relations\":[]}";
        }
        if (objectStart >= 0 && (arrayStart < 0 || objectStart < arrayStart)) {
            int objectEnd = trimmed.lastIndexOf('}');
            return objectEnd >= objectStart ? trimmed.substring(objectStart, objectEnd + 1) : "{\"relations\":[]}";
        }
        int arrayEnd = trimmed.lastIndexOf(']');
        return arrayEnd >= arrayStart ? trimmed.substring(arrayStart, arrayEnd + 1) : "{\"relations\":[]}";
    }

    private static String text(JsonNode node, String field) {
        return node.has(field) && !node.get(field).isNull() ? node.get(field).asText("").trim() : "";
    }

    ValidatedRelation validateRelation(
            RawRelation raw,
            KnowledgeEntry expectedSource,
            Map<String, KnowledgeEntry> titleIndex) {
        String relationType = normalizeRelationType(raw.relationType());
        KnowledgeEntry source = titleIndex.get(normalizeTitle(raw.sourceTitle()));
        KnowledgeEntry target = titleIndex.get(normalizeTitle(raw.targetTitle()));
        List<String> errors = new ArrayList<>();

        if (source == null || !Objects.equals(source.getId(), expectedSource.getId())) {
            errors.add("source_mismatch");
            source = expectedSource;
        }
        if (target == null) {
            errors.add("target_not_found");
        }
        if (target != null && Objects.equals(source.getId(), target.getId())) {
            errors.add("self_relation");
        }
        if (!ALLOWED_RELATION_TYPES.contains(relationType)) {
            errors.add("unsupported_relation_type");
        }
        if (raw.evidence() == null || raw.evidence().isBlank()) {
            errors.add("missing_evidence");
        } else if (!evidenceAppearsInEntries(raw.evidence(), source, target)) {
            errors.add("evidence_not_found");
        }

        String status = decideStatus(raw.confidence(), relationType, errors);
        String reason = appendValidation(raw.reason(), errors);
        return new ValidatedRelation(source, target, relationType, clampConfidence(raw.confidence()), raw.evidence(), reason, status);
    }

    private static String normalizeRelationType(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replace('-', '_');
    }

    private static double clampConfidence(double confidence) {
        if (Double.isNaN(confidence)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, confidence));
    }

    private String decideStatus(double confidence, String relationType, List<String> errors) {
        if (!errors.isEmpty() || confidence < rejectThreshold) {
            return "rejected";
        }
        if (confidence >= autoAcceptThreshold && AUTO_ACCEPT_RELATION_TYPES.contains(relationType)) {
            return "accepted";
        }
        return "pending";
    }

    private static String appendValidation(String reason, List<String> errors) {
        if (errors.isEmpty()) {
            return reason == null ? "" : reason;
        }
        String prefix = reason == null || reason.isBlank() ? "" : reason + "\n";
        return prefix + "validation_errors=" + String.join(",", errors);
    }

    static boolean evidenceAppearsInEntries(String evidence, KnowledgeEntry source, KnowledgeEntry target) {
        String normalizedEvidence = normalizeText(evidence);
        if (normalizedEvidence.isBlank()) {
            return false;
        }
        return containsNormalized(source != null ? source.getContent() : null, normalizedEvidence)
                || containsNormalized(target != null ? target.getContent() : null, normalizedEvidence);
    }

    private static boolean containsNormalized(String content, String normalizedEvidence) {
        return content != null && normalizeText(content).contains(normalizedEvidence);
    }

    private KGRelationCandidate saveCandidate(Long projectId, ValidatedRelation relation) {
        Long sourceEntryId = relation.source() != null ? relation.source().getId() : null;
        Long targetEntryId = relation.target() != null ? relation.target().getId() : null;
        KGRelationCandidate existing = findExistingCandidate(projectId, sourceEntryId, targetEntryId, relation);
        if (existing != null) {
            existing.setConfidence(relation.confidence());
            existing.setReason(relation.reason());
            existing.setGraphVersion(System.currentTimeMillis());
            existing.setStatus(relation.status());
            if (existing.getCreatedAt() == null) {
                existing.setCreatedAt(now());
            }
            kgRelationCandidateMapper.updateById(existing);
            return existing;
        }

        KGRelationCandidate candidate = new KGRelationCandidate();
        candidate.setProjectId(projectId);
        candidate.setSourceEntryId(sourceEntryId);
        candidate.setTargetEntryId(targetEntryId);
        candidate.setRelationType(relation.relationType());
        candidate.setConfidence(relation.confidence());
        candidate.setEvidence(relation.evidence());
        candidate.setReason(relation.reason());
        candidate.setExtractor("llm");
        candidate.setGraphVersion(System.currentTimeMillis());
        candidate.setStatus(relation.status());
        candidate.setCreatedAt(now());
        kgRelationCandidateMapper.insert(candidate);
        return candidate;
    }

    private KGRelationCandidate findExistingCandidate(
            Long projectId,
            Long sourceEntryId,
            Long targetEntryId,
            ValidatedRelation relation) {
        LambdaQueryWrapper<KGRelationCandidate> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(KGRelationCandidate::getProjectId, projectId);
        if (sourceEntryId == null) {
            wrapper.isNull(KGRelationCandidate::getSourceEntryId);
        } else {
            wrapper.eq(KGRelationCandidate::getSourceEntryId, sourceEntryId);
        }
        if (targetEntryId == null) {
            wrapper.isNull(KGRelationCandidate::getTargetEntryId);
        } else {
            wrapper.eq(KGRelationCandidate::getTargetEntryId, targetEntryId);
        }
        wrapper.eq(KGRelationCandidate::getRelationType, relation.relationType());
        if (relation.evidence() == null) {
            wrapper.isNull(KGRelationCandidate::getEvidence);
        } else {
            wrapper.eq(KGRelationCandidate::getEvidence, relation.evidence());
        }
        wrapper.eq(KGRelationCandidate::getExtractor, "llm");
        wrapper.last("LIMIT 1");
        return kgRelationCandidateMapper.selectOne(wrapper);
    }

    private void saveFailureCandidate(Long projectId, KnowledgeEntry sourceEntry, String message) {
        KGRelationCandidate candidate = new KGRelationCandidate();
        candidate.setProjectId(projectId);
        candidate.setSourceEntryId(sourceEntry.getId());
        candidate.setRelationType("extract_failed");
        candidate.setConfidence(0.0);
        candidate.setReason(message == null ? "unknown_error" : message);
        candidate.setExtractor("llm");
        candidate.setStatus("rejected");
        candidate.setCreatedAt(now());
        kgRelationCandidateMapper.insert(candidate);
    }

    private boolean promoteCandidate(KGRelationCandidate candidate, Map<Long, KGNode> nodeByEntryId) {
        KGNode sourceNode = nodeByEntryId.get(candidate.getSourceEntryId());
        KGNode targetNode = nodeByEntryId.get(candidate.getTargetEntryId());
        if (sourceNode == null || targetNode == null || Objects.equals(sourceNode.getId(), targetNode.getId())) {
            return false;
        }
        KGEdge edge = new KGEdge();
        edge.setProjectId(candidate.getProjectId());
        edge.setSourceId(sourceNode.getId());
        edge.setTargetId(targetNode.getId());
        edge.setEdgeType("semantic_" + candidate.getRelationType());
        edge.setWeight(candidate.getConfidence() != null ? candidate.getConfidence() : 1.0);
        kgEdgeMapper.insert(edge);
        return true;
    }

    private static Set<String> parseKeywords(String keywords) {
        if (keywords == null || keywords.isBlank()) {
            return Set.of();
        }
        Set<String> result = new LinkedHashSet<>();
        for (String keyword : keywords.split("[,，、;；\\s]+")) {
            String normalized = normalizeText(keyword);
            if (!normalized.isBlank()) {
                result.add(normalized);
            }
        }
        return result;
    }

    private static String normalizeTitle(String title) {
        return normalizeText(title);
    }

    private static String normalizeText(String text) {
        return text == null ? "" : text.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private String now() {
        return LocalDateTime.now().format(TIME_FORMAT);
    }

    record RawRelation(
            String sourceTitle,
            String targetTitle,
            String relationType,
            String evidence,
            double confidence,
            String reason) {
    }

    record ValidatedRelation(
            KnowledgeEntry source,
            KnowledgeEntry target,
            String relationType,
            double confidence,
            String evidence,
            String reason,
            String status) {
    }

    private record ScoredEntry(KnowledgeEntry entry, double score) {
    }

    private static class ExtractionSummary {
        private final Long projectId;
        private final int selectedEntries;
        private int processedEntries;
        private int failedEntries;
        private int entriesWithoutTargets;
        private int rawRelations;
        private int savedCandidates;
        private int promotedEdges;
        private final Map<String, Integer> statusCounts = new LinkedHashMap<>();

        private ExtractionSummary(Long projectId, int selectedEntries) {
            this.projectId = projectId;
            this.selectedEntries = selectedEntries;
        }

        private void incrementStatus(String status) {
            statusCounts.merge(status, 1, Integer::sum);
        }

        private Map<String, Object> toMap() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "succeeded");
            result.put("projectId", projectId);
            result.put("selectedEntries", selectedEntries);
            result.put("processedEntries", processedEntries);
            result.put("failedEntries", failedEntries);
            result.put("entriesWithoutTargets", entriesWithoutTargets);
            result.put("rawRelations", rawRelations);
            result.put("savedCandidates", savedCandidates);
            result.put("promotedEdges", promotedEdges);
            result.put("candidateStatusCounts", statusCounts);
            return result;
        }
    }
}
