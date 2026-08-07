package com.intelligence.platform.controller;

import com.intelligence.platform.entity.Setting;
import com.intelligence.platform.mapper.SettingMapper;
import com.intelligence.platform.service.WebSearchService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 搜索配置管理（后台"系统配置 > 网络搜索"使用）
 */
@RestController
@RequestMapping("/api/search-config")
@CrossOrigin(origins = "*")
public class SearchConfigController {

    @Autowired
    private SettingMapper settingMapper;
    @Autowired
    private WebSearchService webSearchService;

    private static final String PREFIX = "search_";

    /**
     * 获取搜索配置
     */
    @GetMapping
    public Map<String, String> getSearchConfig() {
        List<Setting> all = settingMapper.selectList(null);
        return all.stream()
                .filter(s -> s.getSettingKey() != null && s.getSettingKey().startsWith(PREFIX))
                .collect(Collectors.toMap(
                        s -> s.getSettingKey().substring(PREFIX.length()),
                        Setting::getValue,
                        (a, b) -> b
                ));
    }

    /**
     * 保存搜索配置
     */
    @PutMapping
    public Map<String, Object> saveSearchConfig(@RequestBody Map<String, String> config) {
        for (Map.Entry<String, String> entry : config.entrySet()) {
            String key = PREFIX + entry.getKey();
            Setting existing = settingMapper.selectById(key);
            if (existing != null) {
                existing.setValue(entry.getValue());
                settingMapper.updateById(existing);
            } else {
                Setting s = new Setting();
                s.setSettingKey(key);
                s.setValue(entry.getValue());
                settingMapper.insert(s);
            }
        }
        return Map.of("message", "搜索配置已保存");
    }

    /**
     * 获取搜索开关状态
     */
    @GetMapping("/enabled")
    public Map<String, Object> getSearchEnabled() {
        return Map.of("enabled", webSearchService.isSearchEnabled());
    }

    /**
     * 设置搜索开关
     */
    @PutMapping("/enabled")
    public Map<String, Object> setSearchEnabled(@RequestBody Map<String, Boolean> body) {
        Boolean enabled = body.get("enabled");
        if (enabled == null) {
            return Map.of("error", "缺少enabled参数");
        }
        String key = "search_enabled";
        Setting existing = settingMapper.selectById(key);
        if (existing != null) {
            existing.setValue(String.valueOf(enabled));
            settingMapper.updateById(existing);
        } else {
            Setting s = new Setting();
            s.setSettingKey(key);
            s.setValue(String.valueOf(enabled));
            s.setDescription("网络搜索开关（true=启用，false=禁用）");
            settingMapper.insert(s);
        }
        return Map.of("message", "搜索开关已" + (enabled ? "启用" : "禁用"), "enabled", enabled);
    }

    /**
     * 获取支持的搜索引擎列表
     */
    @GetMapping("/providers")
    public List<Map<String, String>> getProviders() {
        return List.of(
                Map.of("value", "baidu", "label", "百度搜索", "desc", "中国最大搜索引擎，无需API密钥"),
                Map.of("value", "sogou", "label", "搜狗搜索", "desc", "搜狗网页搜索，无需API密钥"),
                Map.of("value", "google", "label", "Google Custom Search", "desc", "需要API Key和CX"),
                Map.of("value", "brave", "label", "Brave Search", "desc", "需要API Key"),
                Map.of("value", "tavily", "label", "Tavily", "desc", "专为AI搜索设计的API"),
                Map.of("value", "serpapi", "label", "SerpApi", "desc", "聚合多个搜索引擎"),
                Map.of("value", "searxng", "label", "SearXNG", "desc", "自托管搜索引擎"),
                Map.of("value", "duckduckgo", "label", "DuckDuckGo", "desc", "免费，无需API密钥")
        );
    }

    /**
     * 测试搜索（发送真实搜索请求）
     */
    @PostMapping("/test")
    public Map<String, Object> testSearch(@RequestBody(required = false) Map<String, String> body) {
        String testQuery = (body != null && body.containsKey("query")) ? body.get("query") : "Spring Boot";
        try {
            List<WebSearchService.SearchResult> results = webSearchService.search(testQuery, 3);
            if (results.isEmpty()) {
                return Map.of("success", false, "message", "搜索完成但未返回结果，请检查配置", "count", 0);
            }
            List<Map<String, String>> preview = results.stream().limit(3).map(r ->
                    Map.of("title", r.title(), "url", r.url(), "snippet",
                            r.snippet().length() > 80 ? r.snippet().substring(0, 80) + "..." : r.snippet())
            ).collect(Collectors.toList());
            return Map.of("success", true, "message", "搜索成功，返回 " + results.size() + " 条结果", "count", results.size(), "results", preview);
        } catch (Exception e) {
            return Map.of("success", false, "message", "搜索测试失败: " + e.getMessage());
        }
    }
}
