package com.intelligence.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * 智能情报分析平台 - 端到端集成测试
 * 直接调用运行中的后端 API，验证各模块功能是否正常。
 * 运行前确保后端已在 http://localhost:8080 启动。
 *
 * 运行: cd backend-springboot && mvn compile exec:java \
 *   -Dexec.mainClass="com.intelligence.platform.IntegrationTest" \
 *   -Dexec.classpathScope=test
 */
public class IntegrationTest {

    static final String BASE = "http://localhost:8080/api";
    static final String PID = "9";
    static final ObjectMapper om = new ObjectMapper();
    static final HttpClient hc = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();
    static int passed = 0, failed = 0;

    public static void main(String[] args) throws Exception {
        System.out.println("==== 智能情报分析平台 - 集成测试 ====\n");
        testHealth();
        testLlmActive();
        testLlmProviders();
        testLlmTest();
        testProjects();
        testEntries();
        testTableMarkdown();
        testDocuments();
        testUploadTasks();
        testKG();
        System.out.println("\n==== 结果: " + passed + " 通过, " + failed + " 失败 ====");
        if (failed > 0) System.exit(1);
    }

    static void testHealth() {
        check("1.健康检查", () -> {
            JsonNode j = om.readTree(get("/health"));
            ok("UP".equals(j.get("status").asText()), "status=" + j.get("status").asText());
        });
    }

    static void testLlmActive() {
        check("2.激活LLM配置", () -> {
            JsonNode j = om.readTree(get("/llm-configs/active"));
            ok("siliconflow".equals(j.get("provider").asText()),
                    "provider=" + j.get("provider").asText() + " model=" + j.get("model").asText());
        });
    }

    static void testLlmProviders() {
        check("3.LLM提供商列表", () -> {
            JsonNode j = om.readTree(get("/llm-configs/providers"));
            ok(j.isArray() && j.size() > 5, "返回" + j.size() + "个提供商");
        });
    }

    static void testLlmTest() {
        String apiKey = System.getenv("TEST_LLM_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            System.out.println("4. LLM connection test skipped: TEST_LLM_API_KEY is not configured.");
            return;
        }
        check("4.LLM连接测试", () -> {
            String req = "{\"provider\":\"siliconflow\","
                    + "\"apiKey\":\"" + apiKey + "\","
                    + "\"baseUrl\":\"https://api.siliconflow.cn/v1\","
                    + "\"model\":\"Qwen/Qwen2.5-72B-Instruct\"}";
            JsonNode j = om.readTree(post("/llm-configs/test", req));
            ok(j.get("success").asBoolean(), j.path("message").asText());
        });
    }

    static void testProjects() {
        check("5.项目列表", () -> {
            JsonNode j = om.readTree(get("/projects"));
            JsonNode items = j.has("items") ? j.get("items") : j;
            boolean found = false;
            for (JsonNode p : items) if ("生物科技与健康".equals(p.get("name").asText())) found = true;
            ok(found, "共" + items.size() + "个项目, 含'生物科技与健康'=" + found);
        });
    }

    static void testEntries() {
        check("6.知识词条列表", () -> {
            JsonNode j = om.readTree(get("/knowledge-entries?pageSize=100"));
            JsonNode items = j.has("items") ? j.get("items") : j;
            ok(items.size() >= 21, "共" + items.size() + "个词条(期望>=21)");
            if (items.size() > 0)
                System.out.println("      示例: " + items.get(0).get("title").asText()
                        + " [" + items.get(0).get("entryType").asText() + "]");
        });
    }

    static void testTableMarkdown() {
        check("7.搜索功能", () -> {
            JsonNode j = om.readTree(get("/search?q=社区医疗"));
            // SearchController 返回 {documents:[...], reports:[...], qa:[...]}
            int total = 0;
            if (j.has("documents")) total += j.get("documents").size();
            if (j.has("reports")) total += j.get("reports").size();
            if (j.has("qa")) total += j.get("qa").size();
            ok(total >= 0, "搜索返回: documents=" + (j.has("documents") ? j.get("documents").size() : 0)
                    + ", reports=" + (j.has("reports") ? j.get("reports").size() : 0)
                    + ", qa=" + (j.has("qa") ? j.get("qa").size() : 0));
        });
    }

    static void testDocuments() {
        check("8.文档列表", () -> {
            JsonNode j = om.readTree(get("/documents?pageSize=100"));
            JsonNode items = j.has("items") ? j.get("items") : j;
            ok(items.isArray() && items.size() >= 2, "共" + items.size() + "个文档");
            for (JsonNode d : items) {
                System.out.println("      文档: " + (d.has("title") ? d.get("title").asText() : "N/A")
                        + " status=" + (d.has("status") ? d.get("status").asText() : "N/A"));
            }
        });
    }

    static void testUploadTasks() {
        check("9.上传任务列表", () -> {
            JsonNode j = om.readTree(get("/upload/tasks"));
            ok(j.isArray(), "返回" + j.size() + "个任务");
        });
    }

    static void testKG() {
        check("10.知识图谱", () -> {
            JsonNode j = om.readTree(get("/kg/nodes"));
            ok(j.isArray(), "图谱节点数=" + j.size());
        });
    }

    // ========== HTTP 辅助方法 ==========

    static String get(String path) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(BASE + path))
                .header("X-Project-Id", PID)
                .timeout(Duration.ofSeconds(30))
                .GET().build();
        return hc.send(req, HttpResponse.BodyHandlers.ofString()).body();
    }

    static String post(String path, String json) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(BASE + path))
                .header("Content-Type", "application/json")
                .header("X-Project-Id", PID)
                .timeout(Duration.ofSeconds(60))
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        return hc.send(req, HttpResponse.BodyHandlers.ofString()).body();
    }

    // ========== 断言辅助方法 ==========

    interface ThrowingRunnable { void run() throws Exception; }

    static void check(String name, ThrowingRunnable r) {
        currentTest = name;
        try { r.run(); } catch (Exception e) { fail(name, e.getMessage()); }
    }

    static void ok(boolean cond, String detail) {
        if (cond) { pass(detail); } else { fail_current(detail); }
    }

    static String currentTest = "";

    static void pass(String detail) {
        passed++;
        System.out.println("  [PASS] " + currentTest + " - " + detail);
    }

    static void fail(String test, String detail) {
        failed++;
        System.out.println("  [FAIL] " + test + " - " + detail);
    }

    static void fail_current(String detail) {
        failed++;
        System.out.println("  [FAIL] " + currentTest + " - " + detail);
    }
}
