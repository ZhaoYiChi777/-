package com.intelligence.platform.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.intelligence.platform.entity.Setting;
import com.intelligence.platform.mapper.SettingMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 网络搜索服务
 * 支持开关控制、多种国内外搜索引擎
 * 支持：Google / Brave / DuckDuckGo / Tavily / SerpApi / SearXNG / 百度搜索 / 搜狗搜索
 */
@Service
public class WebSearchService {

    @Autowired
    private LlmService llmService;
    @Autowired
    private SettingMapper settingMapper;

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    @Value("${search.provider:duckduckgo}")
    private String defaultProvider;

    /**
     * 网络搜索是否启用（从settings表读取开关）
     */
    public boolean isSearchEnabled() {
        Setting s = settingMapper.selectById("search_enabled");
        // 默认启用，只有明确设置为false才禁用
        return s == null || !"false".equalsIgnoreCase(s.getValue());
    }

    /**
     * 从settings表动态读取搜索配置
     */
    private String getSetting(String key, String defaultValue) {
        Setting s = settingMapper.selectById("search_" + key);
        if (s != null && s.getValue() != null && !s.getValue().isEmpty()) {
            return s.getValue();
        }
        return defaultValue;
    }

    /**
     * 搜索结果
     */
    public record SearchResult(String title, String url, String snippet, String source) {}

    /**
     * 执行网络搜索（从settings表动态读取provider配置，支持开关）
     */
    public List<SearchResult> search(String query, int maxResults) throws Exception {
        // 检查搜索开关
        if (!isSearchEnabled()) {
            return List.of();
        }

        String provider = getSetting("provider", defaultProvider);
        return switch (provider) {
            case "google" -> searchGoogle(query, maxResults);
            case "brave" -> searchBrave(query, maxResults);
            case "tavily" -> searchTavily(query, maxResults);
            case "serpapi" -> searchSerpApi(query, maxResults);
            case "searxng" -> searchSearXNG(query, maxResults);
            case "baidu" -> searchBaidu(query, maxResults);
            case "sogou" -> searchSogou(query, maxResults);
            default -> searchDuckDuckGo(query, maxResults);
        };
    }

    /**
     * Google Custom Search API
     */
    private List<SearchResult> searchGoogle(String query, int maxResults) throws Exception {
        String apiKey = getSetting("google_api_key", "");
        String cx = getSetting("google_cx", "");
        if (apiKey.isEmpty()) {
            return searchDuckDuckGo(query, maxResults);
        }

        String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8);
        String url = String.format(
                "https://www.googleapis.com/customsearch/v1?key=%s&cx=%s&q=%s&num=%d",
                apiKey, cx, encodedQuery, Math.min(maxResults, 10));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        JsonNode root = mapper.readTree(response.body());

        List<SearchResult> results = new ArrayList<>();
        JsonNode items = root.get("items");
        if (items != null && items.isArray()) {
            for (JsonNode item : items) {
                results.add(new SearchResult(
                        item.get("title").asText(),
                        item.get("link").asText(),
                        item.has("snippet") ? item.get("snippet").asText() : "",
                        "google"
                ));
            }
        }
        return results;
    }

    /**
     * Brave Search API
     */
    private List<SearchResult> searchBrave(String query, int maxResults) throws Exception {
        String apiKey = getSetting("api_key", "");
        if (apiKey.isEmpty()) {
            return searchDuckDuckGo(query, maxResults);
        }

        String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8);
        String url = "https://api.search.brave.com/res/v1/web/search?q=" + encodedQuery
                + "&count=" + Math.min(maxResults, 20);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("X-Subscription-Token", apiKey)
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        JsonNode root = mapper.readTree(response.body());

        List<SearchResult> results = new ArrayList<>();
        JsonNode webResults = root.get("web").get("results");
        if (webResults != null && webResults.isArray()) {
            for (JsonNode item : webResults) {
                results.add(new SearchResult(
                        item.get("title").asText(),
                        item.get("url").asText(),
                        item.has("description") ? item.get("description").asText() : "",
                        "brave"
                ));
            }
        }
        return results;
    }

    /**
     * Tavily Search API（专为AI搜索设计）
     */
    private List<SearchResult> searchTavily(String query, int maxResults) throws Exception {
        String apiKey = getSetting("api_key", "");
        if (apiKey.isEmpty()) {
            return searchDuckDuckGo(query, maxResults);
        }

        String url = "https://api.tavily.com/search";
        ObjectNode body = mapper.createObjectNode();
        body.put("api_key", apiKey);
        body.put("query", query);
        body.put("max_results", Math.min(maxResults, 10));
        body.put("include_answer", false);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(15))
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        JsonNode root = mapper.readTree(response.body());

        List<SearchResult> results = new ArrayList<>();
        JsonNode items = root.get("results");
        if (items != null && items.isArray()) {
            for (JsonNode item : items) {
                results.add(new SearchResult(
                        item.get("title").asText(),
                        item.get("url").asText(),
                        item.has("content") ? item.get("content").asText() : "",
                        "tavily"
                ));
            }
        }
        return results;
    }

    /**
     * SerpApi（Google/Bing/DDG搜索结果）
     */
    private List<SearchResult> searchSerpApi(String query, int maxResults) throws Exception {
        String apiKey = getSetting("api_key", "");
        if (apiKey.isEmpty()) {
            return searchDuckDuckGo(query, maxResults);
        }

        String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8);
        String url = "https://serpapi.com/search.json?q=" + encodedQuery
                + "&api_key=" + apiKey + "&num=" + Math.min(maxResults, 10);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        JsonNode root = mapper.readTree(response.body());

        List<SearchResult> results = new ArrayList<>();
        JsonNode organic = root.get("organic_results");
        if (organic != null && organic.isArray()) {
            for (JsonNode item : organic) {
                results.add(new SearchResult(
                        item.get("title").asText(),
                        item.get("link").asText(),
                        item.has("snippet") ? item.get("snippet").asText() : "",
                        "serpapi"
                ));
            }
        }
        return results;
    }

    /**
     * SearXNG（自托管搜索引擎）
     */
    private List<SearchResult> searchSearXNG(String query, int maxResults) throws Exception {
        String baseUrl = getSetting("searxng_url", "");
        if (baseUrl.isEmpty()) {
            return searchDuckDuckGo(query, maxResults);
        }

        String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8);
        String url = baseUrl.replaceAll("/+$", "") + "/search?q=" + encodedQuery + "&format=json";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", "IntelligencePlatform/2.0")
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        JsonNode root = mapper.readTree(response.body());

        List<SearchResult> results = new ArrayList<>();
        JsonNode items = root.get("results");
        if (items != null && items.isArray()) {
            for (JsonNode item : items) {
                if (results.size() >= maxResults) break;
                results.add(new SearchResult(
                        item.get("title").asText(),
                        item.get("url").asText(),
                        item.has("content") ? item.get("content").asText() : "",
                        "searxng"
                ));
            }
        }
        return results;
    }

    /**
     * DuckDuckGo 搜索（免费，使用HTML版本获取实际搜索结果）
     */
    private List<SearchResult> searchDuckDuckGo(String query, int maxResults) throws Exception {
        String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8);
        // 使用 DuckDuckGo HTML 版本获取实际搜索结果
        String url = "https://html.duckduckgo.com/html/?q=" + encodedQuery;

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
                .header("Accept", "text/html,application/xhtml+xml")
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        String html = response.body();

        List<SearchResult> results = new ArrayList<>();

        // 解析HTML中的搜索结果（简化正则解析）
        // 格式: <a class="result__a" href="...">title</a> + <a class="result__snippet">snippet</a>
        int pos = 0;
        while (results.size() < maxResults && pos < html.length()) {
            int titleStart = html.indexOf("class=\"result__a\"", pos);
            if (titleStart == -1) break;

            // 提取URL
            int hrefStart = html.indexOf("href=\"", titleStart - 200);
            if (hrefStart == -1) { pos = titleStart + 1; continue; }
            hrefStart += 6;
            int hrefEnd = html.indexOf("\"", hrefStart);
            if (hrefEnd == -1) break;
            String resultUrl = html.substring(hrefStart, hrefEnd);
            // DuckDuckGo 使用重定向URL
            if (resultUrl.contains("uddg=")) {
                int uddgStart = resultUrl.indexOf("uddg=") + 5;
                int uddgEnd = resultUrl.indexOf("&", uddgStart);
                resultUrl = uddgEnd > 0 ? resultUrl.substring(uddgStart, uddgEnd) : resultUrl.substring(uddgStart);
                resultUrl = java.net.URLDecoder.decode(resultUrl, StandardCharsets.UTF_8);
            }

            // 提取标题
            int tagStart = html.indexOf(">", titleStart) + 1;
            int tagEnd = html.indexOf("</a>", tagStart);
            if (tagEnd == -1) break;
            String title = html.substring(tagStart, tagEnd).replaceAll("<[^>]+>", "").trim();

            // 提取摘要
            int snippetStart = html.indexOf("class=\"result__snippet\"", tagEnd);
            String snippet = "";
            if (snippetStart != -1 && snippetStart < tagEnd + 500) {
                int sStart = html.indexOf(">", snippetStart) + 1;
                int sEnd = html.indexOf("</a>", sStart);
                if (sEnd == -1) sEnd = html.indexOf("</td>", sStart);
                if (sEnd != -1) {
                    snippet = html.substring(sStart, sEnd).replaceAll("<[^>]+>", "").trim();
                }
            }

            if (!title.isEmpty()) {
                results.add(new SearchResult(title, resultUrl, snippet, "duckduckgo"));
            }
            pos = tagEnd + 1;
        }

        // 如果HTML解析失败，回退到Instant Answer API
        if (results.isEmpty()) {
            return searchDuckDuckGoInstant(query, maxResults);
        }
        return results;
    }

    /**
     * DuckDuckGo Instant Answer API（回退方案）
     */
    private List<SearchResult> searchDuckDuckGoInstant(String query, int maxResults) throws Exception {
        String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8);
        String url = "https://api.duckduckgo.com/?q=" + encodedQuery + "&format=json&no_html=1";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36")
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        JsonNode root = mapper.readTree(response.body());

        List<SearchResult> results = new ArrayList<>();

        if (root.has("Abstract") && !root.get("Abstract").asText().isEmpty()) {
            results.add(new SearchResult(
                    root.has("Heading") ? root.get("Heading").asText() : query,
                    root.has("AbstractURL") ? root.get("AbstractURL").asText() : "",
                    root.get("Abstract").asText(),
                    "duckduckgo"
            ));
        }

        JsonNode related = root.get("RelatedTopics");
        if (related != null && related.isArray()) {
            for (JsonNode topic : related) {
                if (results.size() >= maxResults) break;
                if (topic.has("Text") && topic.has("FirstURL")) {
                    results.add(new SearchResult(
                            topic.get("Text").asText().substring(0,
                                    Math.min(60, topic.get("Text").asText().length())),
                            topic.get("FirstURL").asText(),
                            topic.get("Text").asText(),
                            "duckduckgo"
                    ));
                }
            }
        }
        return results;
    }

    /**
     * 百度搜索（通过百度网页搜索API）
     * 支持百度开放平台API或网页抓取
     */
    private List<SearchResult> searchBaidu(String query, int maxResults) throws Exception {
        String apiKey = getSetting("baidu_api_key", "");
        String secretKey = getSetting("baidu_secret_key", "");

        // 如果有API密钥，使用百度开放平台API
        if (!apiKey.isEmpty() && !secretKey.isEmpty()) {
            return searchBaiduApi(query, maxResults, apiKey, secretKey);
        }

        // 否则使用百度网页搜索（HTML解析）
        return searchBaiduHtml(query, maxResults);
    }

    /**
     * 百度开放平台API搜索
     */
    private List<SearchResult> searchBaiduApi(String query, int maxResults, String apiKey, String secretKey) throws Exception {
        // 百度知识图谱API（可用于搜索）
        String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8);
        String url = "https://aip.baidubce.com/rest/2.0/kg/v1/dt/entitylist?q=" + encodedQuery
                + "&access_token=" + getBaiduAccessToken(apiKey, secretKey);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        JsonNode root = mapper.readTree(response.body());

        List<SearchResult> results = new ArrayList<>();
        JsonNode items = root.get("item_list");
        if (items != null && items.isArray()) {
            for (JsonNode item : items) {
                if (results.size() >= maxResults) break;
                results.add(new SearchResult(
                        item.has("name") ? item.get("name").asText() : "",
                        item.has("baikeUrl") ? item.get("baikeUrl").asText() : "",
                        item.has("abstract") ? item.get("abstract").asText() : "",
                        "baidu"
                ));
            }
        }

        // 如果API结果为空，回退到HTML搜索
        if (results.isEmpty()) {
            return searchBaiduHtml(query, maxResults);
        }
        return results;
    }

    /**
     * 获取百度API access token
     */
    private String getBaiduAccessToken(String apiKey, String secretKey) throws Exception {
        String url = "https://aip.baidubce.com/oauth/2.0/token?grant_type=client_credentials"
                + "&client_id=" + apiKey + "&client_secret=" + secretKey;

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        JsonNode root = mapper.readTree(response.body());
        return root.has("access_token") ? root.get("access_token").asText() : "";
    }

    /**
     * 百度HTML搜索（网页抓取）
     */
    private List<SearchResult> searchBaiduHtml(String query, int maxResults) throws Exception {
        String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8);
        String url = "https://www.baidu.com/s?wd=" + encodedQuery + "&rn=" + maxResults;

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
                .header("Accept", "text/html,application/xhtml+xml")
                .header("Accept-Language", "zh-CN,zh;q=0.9")
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        String html = response.body();

        List<SearchResult> results = new ArrayList<>();
        int pos = 0;

        while (results.size() < maxResults && pos < html.length()) {
            // 百度搜索结果格式：<h3 class="t"><a href="...">标题</a></h3>
            int titleStart = html.indexOf("class=\"t\"", pos);
            if (titleStart == -1) titleStart = html.indexOf("class=\"result", pos);
            if (titleStart == -1) break;

            // 提取链接和标题
            int aStart = html.indexOf("<a", titleStart);
            if (aStart == -1 || aStart > titleStart + 500) { pos = titleStart + 1; continue; }

            int hrefStart = html.indexOf("href=\"", aStart);
            if (hrefStart == -1) { pos = aStart + 1; continue; }
            hrefStart += 6;
            int hrefEnd = html.indexOf("\"", hrefStart);
            String resultUrl = hrefEnd > 0 ? html.substring(hrefStart, hrefEnd) : "";

            int tagStart = html.indexOf(">", aStart) + 1;
            int tagEnd = html.indexOf("</a>", tagStart);
            if (tagEnd == -1) break;
            String title = html.substring(tagStart, tagEnd).replaceAll("<[^>]+>", "").trim();

            // 提取摘要
            String snippet = "";
            int snippetStart = html.indexOf("class=\"c-abstract\"", tagEnd);
            if (snippetStart == -1) snippetStart = html.indexOf("class=\"content-right_", tagEnd);
            if (snippetStart != -1 && snippetStart < tagEnd + 1000) {
                int sStart = html.indexOf(">", snippetStart) + 1;
                int sEnd = html.indexOf("</span>", sStart);
                if (sEnd == -1) sEnd = html.indexOf("</div>", sStart);
                if (sEnd != -1) {
                    snippet = html.substring(sStart, sEnd).replaceAll("<[^>]+>", "").trim();
                }
            }

            if (!title.isEmpty()) {
                results.add(new SearchResult(title, resultUrl, snippet, "baidu"));
            }
            pos = tagEnd + 1;
        }

        return results;
    }

    /**
     * 搜狗搜索（支持搜狗网页搜索）
     */
    private List<SearchResult> searchSogou(String query, int maxResults) throws Exception {
        String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8);
        String url = "https://www.sogou.com/web?query=" + encodedQuery;

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
                .header("Accept", "text/html,application/xhtml+xml")
                .header("Accept-Language", "zh-CN,zh;q=0.9")
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        String html = response.body();

        List<SearchResult> results = new ArrayList<>();
        int pos = 0;

        while (results.size() < maxResults && pos < html.length()) {
            // 搜狗搜索结果格式：<h3><a href="...">标题</a></h3>
            int h3Start = html.indexOf("<h3", pos);
            if (h3Start == -1) break;

            int aStart = html.indexOf("<a", h3Start);
            if (aStart == -1 || aStart > h3Start + 200) { pos = h3Start + 1; continue; }

            int hrefStart = html.indexOf("href=\"", aStart);
            if (hrefStart == -1) { pos = aStart + 1; continue; }
            hrefStart += 6;
            int hrefEnd = html.indexOf("\"", hrefStart);
            String resultUrl = hrefEnd > 0 ? html.substring(hrefStart, hrefEnd) : "";

            int tagStart = html.indexOf(">", aStart) + 1;
            int tagEnd = html.indexOf("</a>", tagStart);
            if (tagEnd == -1) break;
            String title = html.substring(tagStart, tagEnd).replaceAll("<[^>]+>", "").trim();

            // 提取摘要
            String snippet = "";
            int snippetStart = html.indexOf("class=\"str-text\"", tagEnd);
            if (snippetStart == -1) snippetStart = html.indexOf("class=\"space-txt\"", tagEnd);
            if (snippetStart != -1 && snippetStart < tagEnd + 500) {
                int sStart = html.indexOf(">", snippetStart) + 1;
                int sEnd = html.indexOf("</span>", sStart);
                if (sEnd == -1) sEnd = html.indexOf("</div>", sStart);
                if (sEnd != -1) {
                    snippet = html.substring(sStart, sEnd).replaceAll("<[^>]+>", "").trim();
                }
            }

            if (!title.isEmpty()) {
                results.add(new SearchResult(title, resultUrl, snippet, "sogou"));
            }
            pos = tagEnd + 1;
        }

        return results;
    }

    /**
     * 生成搜索查询列表（参考 llm_wiki optimize-research-topic.ts）
     * 根据研究主题生成多个搜索查询
     */
    public List<String> generateSearchQueries(String topic) throws Exception {
        String systemPrompt = """
                你是一个研究助手。请根据给定的研究主题，生成3-5个精确的搜索查询。
                每个查询应该从不同角度探索这个主题。
                只返回JSON数组格式：["查询1", "查询2", ...]
                """;

        String response = llmService.chatWithActive(systemPrompt,
                "研究主题: " + topic + "\n请生成搜索查询。");

        List<String> queries = new ArrayList<>();
        try {
            String json = response.trim();
            if (json.startsWith("```")) {
                json = json.replaceAll("^```\\w*\\n?", "").replaceAll("\\n?```$", "");
            }
            JsonNode arr = mapper.readTree(json);
            if (arr.isArray()) {
                for (JsonNode node : arr) {
                    queries.add(node.asText());
                }
            }
        } catch (Exception e) {
            queries.add(topic);
        }
        return queries;
    }
}
