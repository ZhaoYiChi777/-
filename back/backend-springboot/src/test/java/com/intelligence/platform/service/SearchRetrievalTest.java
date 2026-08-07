package com.intelligence.platform.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.intelligence.platform.controller.QAChatController;
import com.intelligence.platform.entity.KGEdge;
import com.intelligence.platform.entity.KGNode;
import com.intelligence.platform.entity.KnowledgeEntry;
import com.intelligence.platform.mapper.KGEdgeMapper;
import com.intelligence.platform.mapper.KGNodeMapper;
import com.intelligence.platform.mapper.KnowledgeEntryMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.lang.reflect.Method;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
public class SearchRetrievalTest {

    @Autowired
    private QAChatController qaChatController;

    @Autowired
    private KGService kgService;

    @Autowired
    private KnowledgeEntryMapper knowledgeEntryMapper;

    @Autowired
    private KGNodeMapper kgNodeMapper;

    @Autowired
    private KGEdgeMapper kgEdgeMapper;

    @Autowired
    private ProjectContext projectContext;

    private static final Long TEST_PROJECT_ID = 99999L;
    private final List<Long> entryIdsToClean = new ArrayList<>();

    @BeforeEach
    public void setUp() {
        System.out.println(">>> [TEST SETUP] Setting current project to: " + TEST_PROJECT_ID);
        projectContext.setCurrentProjectId(TEST_PROJECT_ID);
        cleanupDatabase();
    }

    @AfterEach
    public void tearDown() {
        System.out.println(">>> [TEST CLEANUP] Removing temporary test records...");
        cleanupDatabase();
    }

    private void cleanupDatabase() {
        // Clean knowledge entries
        knowledgeEntryMapper.delete(new LambdaQueryWrapper<KnowledgeEntry>()
                .eq(KnowledgeEntry::getProjectId, TEST_PROJECT_ID));
        // Clean KG Nodes
        kgNodeMapper.delete(new LambdaQueryWrapper<KGNode>()
                .eq(KGNode::getProjectId, TEST_PROJECT_ID));
        // Clean KG Edges
        kgEdgeMapper.delete(new LambdaQueryWrapper<KGEdge>()
                .eq(KGEdge::getProjectId, TEST_PROJECT_ID));
    }

    @Test
    public void testTokenizationAndScoring() throws Exception {
        System.out.println("--- 步骤 1: 验证 CJK Bi-gram 分词器 ---");
        Method tokenizeMethod = QAChatController.class.getDeclaredMethod("tokenizeQuery", String.class);
        tokenizeMethod.setAccessible(true);

        @SuppressWarnings("unchecked")
        List<String> tokens = (List<String>) tokenizeMethod.invoke(qaChatController, "智能医疗与健康管理系统");
        System.out.println("分词结果: " + tokens);

        // Bi-grams expected: "智能", "能医", "医疗", "健康", "康管", "管理", "理系", "系统"
        assertTrue(tokens.contains("智能"), "应包含 '智能'");
        assertTrue(tokens.contains("医疗"), "应包含 '医疗'");
        assertTrue(tokens.contains("健康"), "应包含 '健康'");
        assertTrue(tokens.contains("管理"), "应包含 '管理'");
        assertTrue(tokens.contains("系统"), "应包含 '系统'");

        System.out.println("--- 步骤 2: 验证关键词评分规则 (包含短语加分和 Token 匹配加分) ---");
        Method scoreMethod = QAChatController.class.getDeclaredMethod("scoreEntryKeyword", KnowledgeEntry.class, List.class, String.class);
        scoreMethod.setAccessible(true);

        KnowledgeEntry entry = new KnowledgeEntry();
        entry.setTitle("智能医疗技术");
        entry.setContent("远程医疗是智能医疗技术的重要组成部分，致力于提升健康水平。");
        entry.setKeywords("智能, 医疗, 技术");

        double score = (double) scoreMethod.invoke(qaChatController, entry, tokens, "智能医疗");
        System.out.println("条目得分为: " + score);
        assertTrue(score > 0.0, "评分应大于 0");
    }

    @Test
    public void testHybridSearchAndGraphExpansionAndCitations() throws Exception {
        System.out.println("--- 步骤 3: 插入 12 个测试条目进行混合检索和图谱扩展测试 ---");

        // We insert 12 entries so we can test returning >10 citation sources.
        for (int i = 1; i <= 12; i++) {
            KnowledgeEntry e = new KnowledgeEntry();
            e.setProjectId(TEST_PROJECT_ID);
            e.setStatus("approved");
            e.setEntryType("concept");
            
            if (i == 1) {
                e.setTitle("智能医疗技术简介");
                e.setContent("智能医疗是结合了人工智能的先进医疗科技。");
                e.setKeywords("智能, 医疗");
            } else if (i == 2) {
                e.setTitle("健康管理与智能医疗");
                e.setContent("健康管理常常与 [[智能医疗技术简介]] 一起使用。");
                e.setKeywords("健康, 管理");
                e.setSourceName("健康指南.pdf");
            } else if (i == 3) {
                e.setTitle("配药机器人应用");
                e.setContent("配药机器人是 [[智能医疗技术简介]] 的分支。");
                e.setKeywords("配药, 机器人");
                e.setSourceName("健康指南.pdf"); // Same source as i==2 to test source overlap!
            } else {
                e.setTitle("其他医疗条目 " + i);
                e.setContent("这是测试用的普通医疗内容，编号 " + i);
                e.setKeywords("医疗, 测试");
            }
            
            knowledgeEntryMapper.insert(e);
            entryIdsToClean.add(e.getId());
        }

        System.out.println("--- 步骤 4: 执行检索，验证 RRF 混合检索 + 图谱关联词条扩展 (一阶 wiki link + 来源重合) ---");
        Method searchMethod = QAChatController.class.getDeclaredMethod("searchRelevantEntries", String.class, int.class);
        searchMethod.setAccessible(true);

        @SuppressWarnings("unchecked")
        List<KnowledgeEntry> results = (List<KnowledgeEntry>) searchMethod.invoke(qaChatController, "智能医疗", 3);
        
        System.out.println("搜索与扩展返回条目数量: " + results.size());
        for (int i = 0; i < results.size(); i++) {
            System.out.println("  [" + (i + 1) + "] " + results.get(i).getTitle() + " (Type: " + results.get(i).getEntryType() + ")");
        }

        // The query "智能医疗" matches entry 1 directly via keyword.
        // Entry 2 and Entry 3 have wikilinks [[智能医疗技术简介]] to Entry 1, so they should be added by Graph Expansion!
        // Also they share the source "健康指南.pdf" (source overlap), boosting their relevance.
        boolean foundSeed = false;
        boolean foundExpansion = false;
        for (KnowledgeEntry r : results) {
            if ("智能医疗技术简介".equals(r.getTitle())) foundSeed = true;
            if ("健康管理与智能医疗".equals(r.getTitle()) || "配药机器人应用".equals(r.getTitle())) foundExpansion = true;
        }

        assertTrue(foundSeed, "检索结果应包含种子条目 '智能医疗技术简介'");
        assertTrue(foundExpansion, "检索结果应包含图谱扩展出的关联条目");

        System.out.println("--- 步骤 5: 验证引用列表中包含 >10 个条目，且 index 严格连续匹配 ---");
        Method buildSourceListMethod = QAChatController.class.getDeclaredMethod("buildSourceList", List.class);
        buildSourceListMethod.setAccessible(true);

        // We simulate a result list of all 12 entries to verify citations are not truncated at 10.
        List<KnowledgeEntry> allTwelve = knowledgeEntryMapper.selectList(new LambdaQueryWrapper<KnowledgeEntry>()
                .eq(KnowledgeEntry::getProjectId, TEST_PROJECT_ID));
        
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sources = (List<Map<String, Object>>) buildSourceListMethod.invoke(qaChatController, allTwelve);
        
        System.out.println("生成的引用源数量: " + sources.size());
        assertEquals(12, sources.size(), "引用源数量应为 12，不应被截断为 10");

        for (int i = 0; i < sources.size(); i++) {
            Map<String, Object> src = sources.get(i);
            int index = (int) src.get("index");
            assertEquals(i + 1, index, "引用的 index 应为 1-based 连续递增数");
            System.out.println("  引用索引: " + index + " -> " + src.get("title"));
        }
    }

    @Test
    public void testKGServiceGraphBuildingAndEdges() throws Exception {
        System.out.println("--- 步骤 6: 验证 KGService 自动构图的丰富边生成及社区检测 ---");

        // Insert seed test entries
        KnowledgeEntry e1 = new KnowledgeEntry();
        e1.setProjectId(TEST_PROJECT_ID);
        e1.setStatus("approved");
        e1.setEntryType("concept");
        e1.setTitle("智能医疗");
        e1.setContent("医疗行业信息化。");
        e1.setKeywords("智能, 医疗");
        knowledgeEntryMapper.insert(e1);

        KnowledgeEntry e2 = new KnowledgeEntry();
        e2.setProjectId(TEST_PROJECT_ID);
        e2.setStatus("approved");
        e2.setEntryType("concept");
        e2.setTitle("健康系统");
        e2.setContent("用于个人健康管理，包含与 [[智能医疗]] 的联动。");
        e2.setKeywords("健康, 系统");
        e2.setSourceName("文档A.pdf");
        knowledgeEntryMapper.insert(e2);

        KnowledgeEntry e3 = new KnowledgeEntry();
        e3.setProjectId(TEST_PROJECT_ID);
        e3.setStatus("approved");
        e3.setEntryType("concept");
        e3.setTitle("电子病历");
        e3.setContent("电子病历也是系统的重要部分。");
        e3.setKeywords("病历, 系统"); // shares "系统" keyword with e2!
        e3.setSourceName("文档A.pdf"); // shares document with e2!
        knowledgeEntryMapper.insert(e3);

        // Execute KG generation
        Method autoBuildMethod = KGService.class.getDeclaredMethod("autoBuildGraph");
        autoBuildMethod.setAccessible(true);
        
        @SuppressWarnings("unchecked")
        List<KGNode> nodes = (List<KGNode>) autoBuildMethod.invoke(kgService);
        System.out.println("生成的图节点数量: " + nodes.size());
        assertEquals(3, nodes.size());

        List<KGEdge> edges = kgEdgeMapper.selectList(new LambdaQueryWrapper<KGEdge>()
                .eq(KGEdge::getProjectId, TEST_PROJECT_ID));
        
        System.out.println("生成的图边数量: " + edges.size());
        
        boolean foundDirectLink = false;
        boolean foundSourceOverlap = false;
        boolean foundKeywordOverlap = false;

        for (KGEdge edge : edges) {
            System.out.println("  边: Type=" + edge.getEdgeType() + ", Weight=" + edge.getWeight() 
                    + " (" + edge.getSourceId() + " -> " + edge.getTargetId() + ")");
            if ("direct_link".equals(edge.getEdgeType())) foundDirectLink = true;
            if ("source_overlap".equals(edge.getEdgeType())) foundSourceOverlap = true;
            if ("keyword_overlap".equals(edge.getEdgeType())) foundKeywordOverlap = true;
        }

        assertTrue(foundDirectLink, "应生成 direct_link 类型的边 (从 [[智能医疗]] 的 wikilink 解析出来)");
        assertTrue(foundSourceOverlap, "应生成 source_overlap 类型的边 (共享 文档A.pdf 产生)");
        assertTrue(foundKeywordOverlap, "应生成 keyword_overlap 类型的边 (共享 '系统' 关键词产生)");
    }
}
