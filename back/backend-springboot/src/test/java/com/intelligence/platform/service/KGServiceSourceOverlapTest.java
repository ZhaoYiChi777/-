package com.intelligence.platform.service;

import com.intelligence.platform.entity.KGEdge;
import com.intelligence.platform.entity.KGNode;
import com.intelligence.platform.entity.KnowledgeEntry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KGServiceSourceOverlapTest {

    @Test
    void largeSourceGroupUsesBoundedLinearEdges() {
        List<KnowledgeEntry> entries = new ArrayList<>();
        List<KGNode> nodes = new ArrayList<>();

        for (long id = 1; id <= 6; id++) {
            KnowledgeEntry entry = new KnowledgeEntry();
            entry.setId(id);
            entry.setDocumentId(100L);
            entry.setSourceName("Report-A");
            entries.add(entry);

            KGNode node = new KGNode();
            node.setId(id);
            nodes.add(node);
        }

        List<KGEdge> edges = KGService.buildSourceOverlapEdges(entries, nodes, 7L);

        assertEquals(9, edges.size());
        assertTrue(edges.stream().allMatch(edge -> "source_overlap".equals(edge.getEdgeType())));
        assertTrue(edges.stream().allMatch(edge -> edge.getProjectId().equals(7L)));

        Map<Long, Long> degreeCounts = edges.stream()
                .flatMap(edge -> java.util.stream.Stream.of(edge.getSourceId(), edge.getTargetId()))
                .collect(Collectors.groupingBy(id -> id, Collectors.counting()));
        assertTrue(degreeCounts.values().stream().allMatch(degree -> degree <= 4));
    }

    @Test
    void overlappingSourceSignalsAreMergedIntoOneWeightedEdge() {
        KnowledgeEntry first = entry(1L, 100L, "Report-A");
        KnowledgeEntry second = entry(2L, 100L, " report-a ");

        List<KGEdge> edges = KGService.buildSourceOverlapEdges(
                List.of(first, second),
                List.of(node(1L), node(2L)),
                7L
        );

        assertEquals(1, edges.size());
        assertEquals(2.0, edges.get(0).getWeight());
    }

    @Test
    void entriesWithoutSourceIdentityDoNotCreateSourceEdges() {
        List<KGEdge> edges = KGService.buildSourceOverlapEdges(
                List.of(entry(1L, null, null), entry(2L, null, " ")),
                List.of(node(1L), node(2L)),
                7L
        );

        assertTrue(edges.isEmpty());
    }

    @Test
    void sourceMembershipsPreferDocumentIdentityAndReuseOneSourceNode() {
        List<KGService.SourceMembership> memberships = KGService.buildSourceMemberships(
                List.of(
                        entry(1L, 100L, " Report-A.pdf "),
                        entry(2L, 100L, "report-a.pdf"),
                        entry(3L, null, "Source-B")
                ),
                List.of(node(10L), node(11L), node(12L))
        );

        assertEquals(3, memberships.size());
        assertEquals("document:100", memberships.get(0).sourceKey());
        assertEquals("document:100", memberships.get(1).sourceKey());
        assertEquals("Report-A.pdf", memberships.get(0).sourceLabel());
        assertEquals("source:source-b", memberships.get(2).sourceKey());
        assertEquals("Source-B", memberships.get(2).sourceLabel());
    }

    @Test
    void sourceMembershipsIgnoreEntriesWithoutSourceIdentity() {
        List<KGService.SourceMembership> memberships = KGService.buildSourceMemberships(
                List.of(entry(1L, null, null), entry(2L, null, " ")),
                List.of(node(10L), node(11L))
        );

        assertTrue(memberships.isEmpty());
    }

    @Test
    void entryHashIsStableForEquivalentEntryData() {
        KnowledgeEntry first = entry(1L, 100L, "Report-A");
        first.setTitle("Contract termination");
        first.setContent("A party may terminate the contract after material breach.");
        first.setKeywords("contract,termination,breach");

        KnowledgeEntry second = entry(2L, 100L, "Report-A");
        second.setTitle("Contract termination");
        second.setContent("A party may terminate the contract after material breach.");
        second.setKeywords("contract,termination,breach");

        assertEquals(KGService.buildEntryHash(first), KGService.buildEntryHash(second));
    }

    @Test
    void entryHashChangesWhenGraphRelevantContentChanges() {
        KnowledgeEntry entry = entry(1L, 100L, "Report-A");
        entry.setTitle("Contract termination");
        entry.setContent("A party may terminate the contract after material breach.");

        String originalHash = KGService.buildEntryHash(entry);
        entry.setContent("A party may terminate the contract after a material breach with notice.");

        assertNotEquals(originalHash, KGService.buildEntryHash(entry));
    }

    private static KnowledgeEntry entry(Long id, Long documentId, String sourceName) {
        KnowledgeEntry entry = new KnowledgeEntry();
        entry.setId(id);
        entry.setDocumentId(documentId);
        entry.setSourceName(sourceName);
        return entry;
    }

    private static KGNode node(Long id) {
        KGNode node = new KGNode();
        node.setId(id);
        return node;
    }
}
