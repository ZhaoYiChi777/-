package com.intelligence.platform.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * MinerU 远程 API 客户端（参考 llm_wiki/src/lib/mineru.ts）
 * 直接调用 https://mineru.net/api/v4/ 解析文档为 Markdown
 * 支持：PDF、DOCX、PPTX、HTML、图片等格式
 *
 * 流程：
 * 1. URL解析：POST /extract/task → poll /extract/task/{taskId} → 下载zip
 * 2. 文件上传：POST /file-urls/batch → PUT文件到presigned URL → poll /extract-results/batch/{batchId} → 下载zip
 */
@Service
public class MinerUClient {

    private static final Logger log = LoggerFactory.getLogger(MinerUClient.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final String API_BASE = "https://mineru.net/api/v4";
    private static final int POLL_INTERVAL_MS = 3000;
    private static final int POLL_TIMEOUT_MS = 300_000; // 5分钟

    @Autowired
    private com.intelligence.platform.mapper.SettingMapper settingMapper;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .followRedirects(HttpClient.Redirect.ALWAYS)
            .build();

    /**
     * 获取MinerU API Token（从settings表）
     */
    private String getToken() {
        var setting = settingMapper.selectById("mineru_token");
        if (setting == null || setting.getValue() == null || setting.getValue().isEmpty()) {
            return null;
        }
        return setting.getValue();
    }

    /**
     * 检查MinerU API是否可用（token已配置且有效）
     */
    public boolean isAvailable() {
        return getToken() != null;
    }

    /**
     * 通过URL解析文档（适用于在线PDF/网页）
     * @param url 文档URL
     * @param modelVersion pipeline / vlm / MinerU-HTML
     * @return 解析结果
     */
    public ParseResult parseByUrl(String url, String modelVersion) {
        String token = getToken();
        if (token == null) {
            return ParseResult.error("MinerU API token not configured. Set 'mineru_token' in settings.");
        }

        try {
            // Step 1: Submit task
            log.info("[MinerU] Submitting URL task: {}", url);
            String taskId = submitUrlTask(token, url, modelVersion);
            log.info("[MinerU] Task submitted: {}", taskId);

            // Step 2: Poll for completion
            String zipUrl = pollTask(token, taskId);
            log.info("[MinerU] Task done, downloading zip");

            // Step 3: Download and extract markdown from zip
            return downloadAndExtract(zipUrl);

        } catch (Exception e) {
            log.error("[MinerU] parseByUrl error: {}", e.getMessage(), e);
            return ParseResult.error(e.getMessage());
        }
    }

    /**
     * 通过文件上传解析文档（适用于本地PDF/DOCX/PPTX等）
     * @param fileName 文件名
     * @param fileData 文件字节数据
     * @param modelVersion pipeline / vlm / MinerU-HTML
     * @return 解析结果
     */
    public ParseResult parseByUpload(String fileName, byte[] fileData, String modelVersion) {
        String token = getToken();
        if (token == null) {
            return ParseResult.error("MinerU API token not configured. Set 'mineru_token' in settings.");
        }

        try {
            // Step 1: Get presigned upload URL
            log.info("[MinerU] Requesting upload URL for: {}", fileName);
            BatchUploadInfo batchInfo = getBatchUploadUrl(token, fileName, modelVersion);
            log.info("[MinerU] Upload URL obtained, batch: {}", batchInfo.batchId);

            // Step 2: Upload file to presigned URL
            uploadFileToUrl(batchInfo.uploadUrl, fileData);
            log.info("[MinerU] File uploaded: {} bytes", fileData.length);

            // Step 3: Poll batch result
            String zipUrl = pollBatchTask(token, batchInfo.batchId);
            log.info("[MinerU] Batch done, downloading zip");

            // Step 4: Download and extract markdown
            return downloadAndExtract(zipUrl);

        } catch (Exception e) {
            log.error("[MinerU] parseByUpload error: {}", e.getMessage(), e);
            return ParseResult.error(e.getMessage());
        }
    }

    // ======================== Internal API calls ========================

    private String submitUrlTask(String token, String url, String modelVersion) throws Exception {
        String body = mapper.writeValueAsString(Map.of(
                "url", url,
                "model_version", modelVersion != null ? modelVersion : "pipeline"
        ));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(API_BASE + "/extract/task"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + token)
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        JsonNode json = mapper.readTree(response.body());

        assertSuccess(json);
        return json.get("data").get("task_id").asText();
    }

    private BatchUploadInfo getBatchUploadUrl(String token, String fileName, String modelVersion) throws Exception {
        String body = mapper.writeValueAsString(Map.of(
                "files", List.of(Map.of("name", fileName, "data_id", fileName)),
                "model_version", modelVersion != null ? modelVersion : "pipeline"
        ));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(API_BASE + "/file-urls/batch"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + token)
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        JsonNode json = mapper.readTree(response.body());

        assertSuccess(json);
        String batchId = json.get("data").get("batch_id").asText();
        JsonNode urls = json.get("data").get("file_urls");
        if (urls == null || urls.size() == 0) {
            throw new RuntimeException("MinerU did not return upload URL");
        }
        String uploadUrl = urls.get(0).asText();

        return new BatchUploadInfo(batchId, uploadUrl);
    }

    private void uploadFileToUrl(String presignedUrl, byte[] fileData) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(presignedUrl))
                .timeout(Duration.ofMinutes(5))
                .PUT(HttpRequest.BodyPublishers.ofByteArray(fileData))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200 && response.statusCode() != 201) {
            throw new RuntimeException("File upload failed: HTTP " + response.statusCode());
        }
    }

    private String pollTask(String token, String taskId) throws Exception {
        long start = System.currentTimeMillis();

        while (System.currentTimeMillis() - start < POLL_TIMEOUT_MS) {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(API_BASE + "/extract/task/" + taskId))
                    .header("Authorization", "Bearer " + token)
                    .timeout(Duration.ofSeconds(30))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode json = mapper.readTree(response.body());
            assertSuccess(json);

            JsonNode data = json.get("data");
            String state = data.get("state").asText();

            if ("done".equals(state)) {
                String zipUrl = data.has("full_zip_url") ? data.get("full_zip_url").asText() : null;
                if (zipUrl == null || zipUrl.isEmpty()) {
                    throw new RuntimeException("MinerU task done but no zip URL returned");
                }
                return zipUrl;
            }

            if ("failed".equals(state)) {
                String errMsg = data.has("err_msg") ? data.get("err_msg").asText() : "unknown error";
                throw new RuntimeException("MinerU parsing failed: " + errMsg);
            }

            Thread.sleep(POLL_INTERVAL_MS);
        }

        throw new RuntimeException("MinerU parsing timed out after 5 minutes");
    }

    private String pollBatchTask(String token, String batchId) throws Exception {
        long start = System.currentTimeMillis();

        while (System.currentTimeMillis() - start < POLL_TIMEOUT_MS) {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(API_BASE + "/extract-results/batch/" + batchId))
                    .header("Authorization", "Bearer " + token)
                    .timeout(Duration.ofSeconds(30))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode json = mapper.readTree(response.body());
            assertSuccess(json);

            JsonNode results = json.get("data").get("extract_result");
            if (results != null && results.size() > 0) {
                JsonNode first = results.get(0);
                String state = first.get("state").asText();

                if ("done".equals(state)) {
                    String zipUrl = first.has("full_zip_url") ? first.get("full_zip_url").asText() : null;
                    if (zipUrl == null || zipUrl.isEmpty()) {
                        throw new RuntimeException("MinerU batch done but no zip URL returned");
                    }
                    return zipUrl;
                }

                if ("failed".equals(state)) {
                    String errMsg = first.has("err_msg") ? first.get("err_msg").asText() : "unknown error";
                    throw new RuntimeException("MinerU parsing failed: " + errMsg);
                }
            }

            Thread.sleep(POLL_INTERVAL_MS);
        }

        throw new RuntimeException("MinerU batch parsing timed out after 5 minutes");
    }

    private ParseResult downloadAndExtract(String zipUrl) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(zipUrl))
                .timeout(Duration.ofMinutes(2))
                .GET()
                .build();

        HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) {
            throw new RuntimeException("Failed to download MinerU zip: HTTP " + response.statusCode());
        }

        byte[] zipData = response.body();
        String markdown = "";
        List<Map<String, String>> images = new ArrayList<>();
        String contentListJson = null;

        // Extract from zip
        try (ZipInputStream zis = new ZipInputStream(new java.io.ByteArrayInputStream(zipData))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String name = entry.getName();
                if (name.endsWith(".md")) {
                    // Prefer full.md, but accept any .md
                    if (markdown.isEmpty() || name.endsWith("full.md") || name.endsWith("/full.md")) {
                        markdown = new String(zis.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                    }
                } else if (name.equals("content_list.json")) {
                    contentListJson = new String(zis.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                } else if (isImagePath(name)) {
                    images.add(Map.of(
                            "path", name,
                            "caption", ""
                    ));
                }
                zis.closeEntry();
            }
        }

        if (markdown.isEmpty() && contentListJson != null) {
            // Fallback: build markdown from content_list.json
            JsonNode contentList = mapper.readTree(contentListJson);
            StringBuilder sb = new StringBuilder();
            for (JsonNode item : contentList) {
                String type = item.has("type") ? item.get("type").asText() : "";
                if ("text".equals(type)) {
                    sb.append(item.has("text") ? item.get("text").asText() : "").append("\n");
                } else if ("table".equals(type)) {
                    sb.append(item.has("html") ? item.get("html").asText() : "").append("\n");
                }
            }
            markdown = sb.toString();
        }

        return new ParseResult(markdown, images, "mineru-remote", "");
    }

    private void assertSuccess(JsonNode json) throws RuntimeException {
        JsonNode code = json.get("code");
        if (code != null && !code.asText().equals("0") && code.asInt() != 0) {
            String msg = json.has("msg") ? json.get("msg").asText() : "";
            String codeStr = code.asText();

            // Known error codes
            String errorMessage = switch (codeStr) {
                case "A0202" -> "MinerU token is invalid";
                case "A0211" -> "MinerU token has expired";
                case "-60005" -> "File exceeds 200MB limit";
                case "-60006" -> "File exceeds 600 page limit";
                case "-60018" -> "MinerU daily quota reached";
                default -> "MinerU API error " + codeStr;
            };

            throw new RuntimeException(errorMessage + (msg.isEmpty() ? "" : ": " + msg));
        }
    }

    private boolean isImagePath(String name) {
        String lower = name.toLowerCase();
        return lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                || lower.endsWith(".gif") || lower.endsWith(".webp") || lower.endsWith(".bmp")
                || lower.endsWith(".svg") || lower.endsWith(".tif") || lower.endsWith(".tiff");
    }

    // ======================== Result types ========================

    private record BatchUploadInfo(String batchId, String uploadUrl) {}

    /**
     * 解析结果
     */
    public record ParseResult(
            String markdown,
            List<Map<String, String>> images,
            String parser,
            String title
    ) {
        public boolean isSuccess() {
            return !"error".equals(parser) && markdown != null && !markdown.isEmpty();
        }

        public static ParseResult error(String message) {
            return new ParseResult("", Collections.emptyList(), "error", message);
        }
    }
}
