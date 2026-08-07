package com.intelligence.platform.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelligence.platform.common.PageResult;
import com.intelligence.platform.entity.DeepResearch;
import com.intelligence.platform.entity.KnowledgeEntry;
import com.intelligence.platform.mapper.DeepResearchMapper;
import com.intelligence.platform.mapper.KnowledgeEntryMapper;
import com.intelligence.platform.service.LlmService;
import com.intelligence.platform.service.ProjectContext;
import com.intelligence.platform.service.SettingService;
import com.intelligence.platform.service.VectorIndex;
import com.intelligence.platform.service.VectorSearchService;
import com.intelligence.platform.service.WebSearchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

/**
 * 深度研究管理（前台"深度研究"Tab使用）
 * 参考 llm_wiki 的 deep-research.ts
 * 流程：输入主题 → 生成搜索查询 → 网络搜索 → LLM综合分析 → 结构化报告
 */
@RestController
@RequestMapping("/api/deep-researches")
@CrossOrigin(origins = "*")
public class DeepResearchController {

    private static final Logger log = LoggerFactory.getLogger(DeepResearchController.class);

    @Autowired
    private DeepResearchMapper deepResearchMapper;
    @Autowired
    private KnowledgeEntryMapper knowledgeEntryMapper;
    @Autowired
    private ProjectContext projectContext;
    @Autowired
    private LlmService llmService;
    @Autowired
    private WebSearchService webSearchService;
    @Autowired
    private ExecutorService taskExecutor;
    @Autowired
    private SettingService settingService;
    @Autowired
    private VectorSearchService vectorSearchService;

    private final ObjectMapper mapper = new ObjectMapper();

    @GetMapping
    public PageResult<DeepResearch> list(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {

        LambdaQueryWrapper<DeepResearch> wrapper = new LambdaQueryWrapper<>();
        Long pid = projectContext.getCurrentProjectId();
        if (pid != null) wrapper.eq(DeepResearch::getProjectId, pid);
        if (status != null && !status.isEmpty()) wrapper.eq(DeepResearch::getStatus, status);
        wrapper.orderByDesc(DeepResearch::getCreatedAt);

        Page<DeepResearch> pageObj = new Page<>(page, pageSize);
        Page<DeepResearch> result = deepResearchMapper.selectPage(pageObj, wrapper);
        return new PageResult<>(result.getTotal(), page, pageSize, result.getRecords());
    }

    @GetMapping("/{id}")
    public DeepResearch get(@PathVariable Long id) {
        DeepResearch research = deepResearchMapper.selectById(id);
        if (research == null) return null;
        // 项目隔离：仅允许访问当前项目的深度研究
        Long pid = projectContext.getCurrentProjectId();
        if (pid != null && research.getProjectId() != null && !pid.equals(research.getProjectId())) {
            return null; // 不返回其他项目的数据
        }
        return research;
    }

    /**
     * 创建并启动深度研究任务
     * 参考 llm_wiki deep-research.ts 的 queueResearch + processQueue
     */
    @PostMapping
    public Map<String, Object> create(@RequestBody DeepResearch research) {
        String now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        Long pid = projectContext.getCurrentProjectId();
        research.setProjectId(pid);
        research.setStatus("queued");
        research.setProgress(0);
        research.setCreatedAt(now);
        deepResearchMapper.insert(research);

        Long taskId = research.getId();

        // 异步执行研究流程（使用自定义线程池）
        CompletableFuture.runAsync(() -> executeResearch(taskId, research.getTopic(), pid), taskExecutor);

        return Map.of("id", taskId, "status", "queued", "message", "研究任务已创建，正在执行...");
    }

    /**
     * 执行深度研究流程（异步）
     */
    private void executeResearch(Long taskId, String topic, Long projectId) {
        String now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        try {
            // 更新状态为运行中
            deepResearchMapper.update(null,
                    new LambdaUpdateWrapper<DeepResearch>()
                            .eq(DeepResearch::getId, taskId)
                            .set(DeepResearch::getStatus, "running")
                            .set(DeepResearch::getProgress, 10)
            );

            // 1. 生成搜索查询
            List<String> queries = webSearchService.generateSearchQueries(topic);
            deepResearchMapper.update(null,
                    new LambdaUpdateWrapper<DeepResearch>()
                            .eq(DeepResearch::getId, taskId)
                            .set(DeepResearch::getSearchQueries, mapper.writeValueAsString(queries))
                            .set(DeepResearch::getProgress, 25)
            );

            // 2. 执行网络搜索（参考 llm_wiki 的去重逻辑）
            boolean webSearchEnabled = webSearchService.isSearchEnabled();
            StringBuilder allResults = new StringBuilder();
            Set<String> seenUrls = new HashSet<>();
            int totalSources = 0;
            int maxSources = settingService.getInt("research_max_sources", 20); // 最大来源数
            List<String> searchErrors = new ArrayList<>();

            // 只在网络搜索启用时执行外部搜索
            if (webSearchEnabled) {
                for (String query : queries) {
                    if (totalSources >= maxSources) break; // 达到上限，停止搜索

                    try {
                        int maxResultsPerQuery = settingService.getInt("research_max_results_per_query", 10);
                        List<WebSearchService.SearchResult> results = webSearchService.search(query, maxResultsPerQuery);

                        for (WebSearchService.SearchResult r : results) {
                            if (totalSources >= maxSources) break;
                            // 去重：基于URL
                            String urlKey = r.url().toLowerCase();
                            if (seenUrls.contains(urlKey)) continue;
                            seenUrls.add(urlKey);

                            allResults.append("[").append(totalSources + 1).append("] **").append(r.title()).append("**\n");
                            allResults.append("来源: ").append(r.url()).append(" (").append(r.source()).append(")\n");
                            allResults.append(r.snippet()).append("\n\n");
                            totalSources++;
                        }
                    } catch (Exception e) {
                        searchErrors.add("查询 '" + query + "' 失败: " + e.getMessage());
                    }
                }
            } else {
                log.info("网络搜索已禁用，仅使用本地知识库");
            }

            // 2.5 搜索本地知识库（向量语义搜索）
            try {
                List<VectorIndex.SearchResult> localResults = vectorSearchService.search(topic, 5);
                if (!localResults.isEmpty()) {
                    allResults.append("## 本地知识库相关资料\n\n");
                    for (var r : localResults) {
                        totalSources++;
                        allResults.append("[").append(totalSources).append("] **")
                                .append(r.metadata().getOrDefault("title", "本地资料"))
                                .append("**\n");
                        allResults.append("来源: 本地知识库 (").append(r.metadata().getOrDefault("sourceName", "")).append(")\n");
                        allResults.append(r.metadata().getOrDefault("content", "")).append("\n\n");
                    }
                }
            } catch (Exception e) {
                log.warn("本地知识库搜索失败: {}", e.getMessage());
            }

            // 如果所有搜索都失败，标记错误
            if (totalSources == 0 && !searchErrors.isEmpty()) {
                deepResearchMapper.update(null,
                        new LambdaUpdateWrapper<DeepResearch>()
                                .eq(DeepResearch::getId, taskId)
                                .set(DeepResearch::getStatus, "failed")
                                .set(DeepResearch::getError, "所有搜索均失败: " + String.join("; ", searchErrors))
                );
                return;
            }

            deepResearchMapper.update(null,
                    new LambdaUpdateWrapper<DeepResearch>()
                            .eq(DeepResearch::getId, taskId)
                            .set(DeepResearch::getSourceCount, totalSources)
                            .set(DeepResearch::getProgress, 45)
            );

            // 构建用户消息（供深度思考和综合报告共用）
            String userMessage = "研究主题: **" + topic + "**\n\n## 搜索到的资料\n\n" + allResults;

            // 深度思考步骤：对搜索资料进行逐步推理分析
            StringBuilder thinkingPrompt = new StringBuilder();
            thinkingPrompt.append("你是一个专业的深度思考分析师。请对以下研究主题和搜索到的资料进行逐步推理分析。\n\n");
            thinkingPrompt.append("## 推理要求\n");
            thinkingPrompt.append("请按照以下步骤进行思考，每一步都要详细展开：\n");
            thinkingPrompt.append("1. **资料梳理**：总结搜索到的关键资料，识别核心事实和数据\n");
            thinkingPrompt.append("2. **逻辑分析**：分析各资料之间的关联、矛盾和互补关系\n");
            thinkingPrompt.append("3. **深层洞察**：挖掘资料背后的深层逻辑、趋势和隐含信息\n");
            thinkingPrompt.append("4. **假设验证**：对关键假设进行推敲，指出哪些有充分证据支持，哪些仍需验证\n");
            thinkingPrompt.append("5. **初步结论**：基于以上推理，形成初步分析结论\n\n");
            thinkingPrompt.append("请使用中文，推理过程要具体、有逻辑深度，不要泛泛而谈。");

            String thinkingProcess;
            try {
                thinkingProcess = llmService.chatWithActive(thinkingPrompt.toString(), userMessage);
            } catch (Exception llmErr) {
                log.warn("深度思考步骤失败，将跳过: {}", llmErr.getMessage());
                thinkingProcess = "深度思考步骤执行失败，已跳过。";
            }

            deepResearchMapper.update(null,
                    new LambdaUpdateWrapper<DeepResearch>()
                            .eq(DeepResearch::getId, taskId)
                            .set(DeepResearch::getProgress, 60)
                            .set(DeepResearch::getThinkingProcess, thinkingProcess)
            );

            // 获取本地词条索引，用于 [[双链]] 交叉引用 (与 llm_wiki 彻底一致)
            StringBuilder wikiIndex = new StringBuilder();
            if (projectId != null) {
                try {
                    LambdaQueryWrapper<KnowledgeEntry> entryWrapper = new LambdaQueryWrapper<>();
                    entryWrapper.eq(KnowledgeEntry::getProjectId, projectId);
                    List<KnowledgeEntry> entries = knowledgeEntryMapper.selectList(entryWrapper);
                    for (KnowledgeEntry entry : entries) {
                        wikiIndex.append("- ").append(entry.getTitle()).append("\n");
                    }
                } catch (Exception e) {
                    log.warn("获取项目词条索引失败: {}", e.getMessage());
                }
            }

            // 3. LLM综合分析（参考 llm_wiki 的 systemPrompt 设计）
            StringBuilder systemPrompt = new StringBuilder();
            systemPrompt.append("你是一个专业的深度研究分析师。请基于以下搜索到的资料，对研究主题进行综合分析并输出一份详尽的研究报告。\n\n");
            systemPrompt.append("## 交叉引用 (重要)\n");
            systemPrompt.append("- 本项目知识库中已存在以下词条页面。在你的分析报告中，凡是提及这些实体、术语或概念时，请务必使用 [[双括号]] (例如 [[社区医疗]]) 的语法格式进行标注，以便系统自动建立关联引用链接。\n");
            if (wikiIndex.length() > 0) {
                systemPrompt.append("## 已有词条索引 (提及以下词条时，请使用 [[词条名称]] 格式进行双链链接):\n");
                systemPrompt.append(wikiIndex.toString()).append("\n");
            }
            systemPrompt.append("## 写作要求\n");
            systemPrompt.append("- 结构化分析：背景概述、核心发现、趋势研判、风险与挑战、建议\n");
            systemPrompt.append("- 使用 [N] 格式引用来源（如 [1]、[2]），对应资料编号\n");
            systemPrompt.append("- 客观中立，标注不确定性和信息缺口\n");
            systemPrompt.append("- 标注矛盾或争议性观点\n");
            systemPrompt.append("- 建议值得进一步追踪的信息源\n");
            systemPrompt.append("- 使用中文\n");
            systemPrompt.append("- 字数不少于1000字\n");

            systemPrompt.append("\n## 深度思考参考\n");
            systemPrompt.append("以下是你之前的深度思考过程，可以在综合报告中引用关键推理：\n");
            systemPrompt.append(thinkingProcess).append("\n\n");

            String synthesis;
            try {
                synthesis = llmService.chatWithActive(systemPrompt.toString(), userMessage);
            } catch (Exception llmErr) {
                deepResearchMapper.update(null,
                        new LambdaUpdateWrapper<DeepResearch>()
                                .eq(DeepResearch::getId, taskId)
                                .set(DeepResearch::getStatus, "failed")
                                .set(DeepResearch::getError, "LLM综合分析失败: " + llmErr.getMessage()
                                        + "。请确保已在系统配置中启用至少一个LLM配置。")
                );
                return;
            }

            // 4. 完成
            deepResearchMapper.update(null,
                    new LambdaUpdateWrapper<DeepResearch>()
                            .eq(DeepResearch::getId, taskId)
                            .set(DeepResearch::getStatus, "completed")
                            .set(DeepResearch::getProgress, 100)
                            .set(DeepResearch::getSynthesis, synthesis)
                            .set(DeepResearch::getThinkingProcess, thinkingProcess)
                            .set(DeepResearch::getCompletedAt, LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")))
            );

        } catch (Exception e) {
            deepResearchMapper.update(null,
                    new LambdaUpdateWrapper<DeepResearch>()
                            .eq(DeepResearch::getId, taskId)
                            .set(DeepResearch::getStatus, "failed")
                            .set(DeepResearch::getError, e.getMessage())
            );
        }
    }

    /**
     * 取消研究任务
     */
    @PutMapping("/{id}/cancel")
    public Map<String, Object> cancel(@PathVariable Long id) {
        Long pid = projectContext.getCurrentProjectId();
        LambdaUpdateWrapper<DeepResearch> wrapper = new LambdaUpdateWrapper<DeepResearch>()
                .eq(DeepResearch::getId, id);
        if (pid != null) wrapper.eq(DeepResearch::getProjectId, pid);
        deepResearchMapper.update(null,
                wrapper.set(DeepResearch::getStatus, "cancelled"));
        return Map.of("message", "任务已取消");
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable Long id) {
        Long pid = projectContext.getCurrentProjectId();
        LambdaQueryWrapper<DeepResearch> wrapper = new LambdaQueryWrapper<DeepResearch>()
                .eq(DeepResearch::getId, id);
        if (pid != null) wrapper.eq(DeepResearch::getProjectId, pid);
        deepResearchMapper.delete(wrapper);
        return Map.of("message", "删除成功");
    }
}
