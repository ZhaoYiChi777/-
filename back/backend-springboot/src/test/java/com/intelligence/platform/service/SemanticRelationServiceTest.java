package com.intelligence.platform.service;

import com.intelligence.platform.entity.KnowledgeEntry;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SemanticRelationServiceTest {

    @Test
    void parseRelationsAcceptsJsonWrappedInMarkdownFence() throws Exception {
        SemanticRelationService service = serviceWithThresholds();

        List<SemanticRelationService.RawRelation> relations = service.parseRelations("""
                ```json
                {"relations":[{"sourceTitle":"A","targetTitle":"B","relationType":"references","evidence":"A references B","confidence":0.91,"reason":"explicit"}]}
                ```
                """);

        assertEquals(1, relations.size());
        assertEquals("A", relations.get(0).sourceTitle());
        assertEquals("references", relations.get(0).relationType());
        assertEquals(0.91, relations.get(0).confidence());
    }

    @Test
    void evidenceMustAppearInSourceOrTargetContent() {
        KnowledgeEntry source = entry(1L, "A", "A explicitly references B in this paragraph.");
        KnowledgeEntry target = entry(2L, "B", "B is the referenced target.");

        assertTrue(SemanticRelationService.evidenceAppearsInEntries("A explicitly references B", source, target));
        assertFalse(SemanticRelationService.evidenceAppearsInEntries("A causes B without textual support", source, target));
    }

    @Test
    void validatorRejectsHallucinatedEvidenceEvenWithHighConfidence() {
        SemanticRelationService service = serviceWithThresholds();
        KnowledgeEntry source = entry(1L, "A", "A explicitly references B in this paragraph.");
        KnowledgeEntry target = entry(2L, "B", "B is the referenced target.");

        SemanticRelationService.ValidatedRelation relation = service.validateRelation(
                new SemanticRelationService.RawRelation(
                        "A",
                        "B",
                        "references",
                        "A causes B without textual support",
                        0.95,
                        "model guessed"),
                source,
                Map.of("a", source, "b", target)
        );

        assertEquals("rejected", relation.status());
        assertTrue(relation.reason().contains("evidence_not_found"));
    }

    @Test
    void validatorAcceptsOnlyHighConfidenceSafeRelationTypes() {
        SemanticRelationService service = serviceWithThresholds();
        KnowledgeEntry source = entry(1L, "A", "A explicitly references B in this paragraph.");
        KnowledgeEntry target = entry(2L, "B", "B is the referenced target.");

        SemanticRelationService.ValidatedRelation accepted = service.validateRelation(
                new SemanticRelationService.RawRelation(
                        "A",
                        "B",
                        "references",
                        "A explicitly references B",
                        0.91,
                        "explicit reference"),
                source,
                Map.of("a", source, "b", target)
        );
        SemanticRelationService.ValidatedRelation pending = service.validateRelation(
                new SemanticRelationService.RawRelation(
                        "A",
                        "B",
                        "causes",
                        "A explicitly references B",
                        0.91,
                        "type needs review"),
                source,
                Map.of("a", source, "b", target)
        );

        assertEquals("accepted", accepted.status());
        assertEquals("pending", pending.status());
    }

    private static SemanticRelationService serviceWithThresholds() {
        SemanticRelationService service = new SemanticRelationService();
        ReflectionTestUtils.setField(service, "rejectThreshold", 0.65);
        ReflectionTestUtils.setField(service, "autoAcceptThreshold", 0.82);
        return service;
    }

    private static KnowledgeEntry entry(Long id, String title, String content) {
        KnowledgeEntry entry = new KnowledgeEntry();
        entry.setId(id);
        entry.setTitle(title);
        entry.setContent(content);
        return entry;
    }
}
