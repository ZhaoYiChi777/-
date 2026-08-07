package com.intelligence.platform.service;

import com.intelligence.platform.entity.KnowledgeEntry;
import com.intelligence.platform.entity.LlmConfig;
import com.intelligence.platform.mapper.KnowledgeEntryMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 图片处理服务
 * 负责：图片保存、VLM caption生成、从文档提取嵌入图片、表格转markdown
 * 采用 Caption-first 策略：图片→VLM描述→文本嵌入→向量搜索
 */
@Service
public class ImageService {

    private static final Logger log = LoggerFactory.getLogger(ImageService.class);

    @Autowired
    private LlmService llmService;
    @Autowired
    private KnowledgeEntryMapper knowledgeEntryMapper;
    @Autowired
    private VectorSearchService vectorSearchService;
    @Autowired
    private com.intelligence.platform.mapper.SettingMapper settingMapper;

    @Value("${upload.dir:./uploads}")
    private String uploadDir;

    @Value("${parser.service.url:http://localhost:8100}")
    private String parserServiceUrl;

    private final HttpClient parserHttpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /**
     * 获取实际生效的上传目录（优先从数据库读取，回退到配置文件）
     */
    private String getEffectiveUploadDir() {
        try {
            com.intelligence.platform.entity.Setting setting = settingMapper.selectById("upload_dir");
            if (setting != null && setting.getValue() != null && !setting.getValue().isBlank()) {
                return setting.getValue();
            }
        } catch (Exception ignored) {}
        return uploadDir;
    }

    /** 图片最小尺寸（像素），过滤logo/装饰等小图 */
    private static final int MIN_IMAGE_SIZE = 100;
    /** 单个文档最大提取图片数 */
    private static final int MAX_IMAGES_PER_DOC = 500;

    /**
     * 获取 OCR/VLM 配置（优先使用独立配置，确保返回支持 vision 的模型）
     * 配置来源优先级：
     * 1. settings 表中的 ocrConfig（JSON）
     * 2. llm_configs 表中 purpose=ocr
     * 3. llm_configs 表中 purpose=vlm
     * 4. chat 配置（仅当支持 vision 时）
     * 5. 返回 null（触发本地 PaddleOCR-VL 回退）
     */
    private LlmConfig getOcrConfig() {
        // 1. 先从 settings 表读取 ocrConfig
        com.intelligence.platform.entity.Setting setting = settingMapper.selectById("ocrConfig");
        if (setting != null && setting.getValue() != null && !setting.getValue().isEmpty()) {
            try {
                com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                com.fasterxml.jackson.databind.JsonNode node = mapper.readTree(setting.getValue());
                boolean enabled = node.path("enabled").asBoolean(false);
                boolean useMainLlm = node.path("useMainLlm").asBoolean(true);

                if (enabled && !useMainLlm) {
                    LlmConfig config = new LlmConfig();
                    config.setProvider(node.path("provider").asText("custom"));
                    config.setApiKey(node.path("apiKey").asText(""));
                    config.setModel(node.path("model").asText(""));
                    config.setBaseUrl(node.path("baseUrl").asText(""));
                    config.setApiMode(node.path("apiMode").asText("chat_completions"));
                    config.setEnabled(true);
                    return config;
                }
            } catch (Exception e) {
                log.warn("解析 ocrConfig 失败: {}", e.getMessage());
            }
        }

        // 2. 回退到 llm_configs 表中 purpose=ocr / purpose=vlm
        LlmConfig ocrConfig = llmService.getActiveOcrConfig();
        if (ocrConfig != null) {
            return ocrConfig;
        }

        // 3. 检查 chat 配置是否支持 vision
        LlmConfig chatConfig = llmService.getActiveChatConfig();
        if (chatConfig != null && supportsVision(chatConfig)) {
            log.info("OCR 配置：复用支持 vision 的 chat 配置 ({})", chatConfig.getModel());
            return chatConfig;
        }

        // 4. 无可用的 vision 配置，返回 null 让下游触发本地 PaddleOCR-VL 回退
        log.warn("OCR 配置：未找到支持 vision 的 LLM 配置，将使用本地 PaddleOCR-VL 后备");
        return null;
    }

    /**
     * 判断 LLM 配置是否支持 vision（视觉）能力
     * 通过 provider 和 model 名称中的关键词判断
     */
    private boolean supportsVision(LlmConfig config) {
        if (config == null) return false;
        // Anthropic Claude 3+ 系列均支持 vision
        if ("anthropic".equals(config.getProvider())) return true;
        // Google Gemini 支持 vision
        if ("google".equals(config.getProvider())) return true;
        // 通过 model 名称判断
        String model = config.getModel() != null ? config.getModel().toLowerCase() : "";
        return model.contains("-vl") || model.contains("vl-") || model.contains("vision")
                || model.contains("gemini") || model.contains("claude")
                || model.contains("gpt-4o") || model.contains("gpt-4-turbo")
                || model.contains("qwen2-vl") || model.contains("qwen2.5-vl")
                || model.contains("internvl") || model.contains("glm-4v");
    }

    /**
     * 保存图片到磁盘
     * @param imageData 图片字节数据
     * @param docId 所属文档ID
     * @param imageIndex 图片在文档中的序号
     * @param extension 文件扩展名
     * @return 保存后的相对路径
     */
    public String saveImage(byte[] imageData, Long docId, int imageIndex, String extension) throws Exception {
        Path mediaDir = Paths.get(getEffectiveUploadDir(), "media", String.valueOf(docId));
        Files.createDirectories(mediaDir);
        String filename = imageIndex + "." + extension;
        Path filePath = mediaDir.resolve(filename);
        Files.write(filePath, imageData);
        return filePath.toString();
    }

    /**
     * 使用VLM生成图片描述（Caption-first策略）
     * @param imageData 图片字节数据
     * @param mimeType 图片MIME类型
     * @return 图片描述文本
     */
    public String generateCaption(byte[] imageData, String mimeType) {
        try {
            LlmConfig config = getOcrConfig();
            if (config == null) {
                return "[图片描述生成失败：未配置OCR/VLM模型]";
            }

            String base64 = Base64.getEncoder().encodeToString(imageData);
            String systemPrompt = "你是一个专业的图片描述助手。请用2-4句话准确描述图片内容。" +
                    "如果是图表，请描述图表类型、坐标轴标签、数据趋势和关键数据点。" +
                    "如果是流程图，请描述流程步骤。" +
                    "如果是照片，请描述主要内容。" +
                    "请包含图片中可见的所有文字。只输出描述，不要其他内容。";
            String userMessage = "请描述这张图片的内容。";

            return llmService.chatWithVision(config, systemPrompt, userMessage, base64, mimeType);
        } catch (Exception e) {
            log.warn("VLM caption生成失败: {}", e.getMessage());
            return "[图片描述生成失败: " + e.getMessage() + "]";
        }
    }

    /**
     * 从PPTX/DOCX（OOXML格式）中提取嵌入图片
     * @param filePath 文档路径
     * @return 提取的图片列表（包含字节数据和元信息）
     */
    public List<ExtractedImage> extractFromOoxml(Path filePath) {
        List<ExtractedImage> images = new ArrayList<>();
        try (ZipFile zipFile = new ZipFile(filePath.toFile())) {
            String prefix = filePath.toString().toLowerCase().endsWith(".docx") ? "word/media/" : "ppt/media/";
            int index = 0;

            var entries = zipFile.entries();
            while (entries.hasMoreElements() && index < MAX_IMAGES_PER_DOC) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (name.startsWith(prefix) && isImageFile(name)) {
                    byte[] data = zipFile.getInputStream(entry).readAllBytes();
                    if (isValidImage(data)) {
                        String ext = getExtension(name);
                        String mime = getMimeType(ext);
                        images.add(new ExtractedImage(index, ext, mime, data, null));
                        index++;
                    }
                }
            }
        } catch (Exception e) {
            log.warn("从OOXML提取图片失败: {}", e.getMessage());
        }
        return images;
    }

    /**
     * 从旧版PPT（OLE2/HSLF）中提取嵌入图片
     */
    public List<ExtractedImage> extractFromPpt(Path filePath) {
        List<ExtractedImage> images = new ArrayList<>();
        try {
            var fis = new java.io.FileInputStream(filePath.toFile());
            var ppt = new org.apache.poi.hslf.usermodel.HSLFSlideShow(fis);
            int index = 0;
            for (var slide : ppt.getSlides()) {
                if (index >= MAX_IMAGES_PER_DOC) break;
                for (var shape : slide.getShapes()) {
                    if (shape instanceof org.apache.poi.hslf.usermodel.HSLFPictureShape picture) {
                        var pictureData = picture.getPictureData();
                        if (pictureData != null) {
                            byte[] data = pictureData.getData();
                            if (isValidImage(data)) {
                                String ext = pictureData.getContentType().contains("png") ? "png" : "jpg";
                                String mime = getMimeType(ext);
                                images.add(new ExtractedImage(index, ext, mime, data,
                                        (int) slide.getSlideNumber()));
                                index++;
                            }
                        }
                    }
                }
            }
            ppt.close();
            fis.close();
        } catch (Exception e) {
            log.warn("从PPT提取图片失败: {}", e.getMessage());
        }
        return images;
    }

    /**
     * 处理文档中提取的所有图片：保存 → caption → 创建词条
     * @param images 提取的图片列表
     * @param docId 文档ID
     * @param sourceName 来源文档名
     * @param sourceOrigin 来源信息（用户填写或自动提取）
     * @param library 所属资料库
     * @return 创建的图片词条列表
     */
    public List<KnowledgeEntry> processImages(List<ExtractedImage> images, Long docId,
                                                String sourceName, String sourceOrigin, String library) {
        return processImages(images, docId, sourceName, sourceOrigin, library, null);
    }

    public List<KnowledgeEntry> processImages(List<ExtractedImage> images, Long docId,
                                                String sourceName, String sourceOrigin, String library, Long projectId) {
        List<KnowledgeEntry> entries = new ArrayList<>();
        for (ExtractedImage img : images) {
            try {
                // 1. 保存图片到磁盘
                String mediaPath = saveImage(img.data(), docId, img.index(), img.extension());

                // 2. VLM生成caption
                String caption = generateCaption(img.data(), img.mimeType());

                // 3. 创建知识词条（caption作为content，走文本嵌入）
                KnowledgeEntry entry = new KnowledgeEntry();
                String pageSuffix = img.page() != null ? " 第" + img.page() + "页" : "";
                entry.setTitle(sourceName + " 图片#" + (img.index() + 1) + pageSuffix);
                entry.setEntryType("image");
                entry.setEntryLibrary(library);
                entry.setDocumentId(docId);
                entry.setSourceName(sourceName);
                entry.setContent(caption);
                entry.setKeywords("图片,图表,图示");
                entry.setStatus(isAutoApprove() ? "approved" : "pending");
                entry.setConfidence(0.8);
                entry.setCreatedAt(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
                entry.setMediaType("image");
                entry.setMediaPath(mediaPath);
                entry.setSourceOrigin(sourceOrigin != null ? sourceOrigin + pageSuffix : pageSuffix.trim());
                entry.setProjectId(projectId);

                knowledgeEntryMapper.insert(entry);
                vectorSearchService.indexEntry(entry);
                entries.add(entry);
            } catch (Exception e) {
                log.warn("处理图片#{}失败: {}", img.index(), e.getMessage());
            }
        }
        return entries;
    }

    /**
     * 将Excel/CSV表格数据转换为Markdown格式
     */
    public String excelToMarkdown(Path filePath) {
        String filename = filePath.getFileName().toString().toLowerCase();

        // CSV文件特殊处理：直接解析文本
        if (filename.endsWith(".csv")) {
            return csvToMarkdown(filePath);
        }

        try (var fis = new java.io.FileInputStream(filePath.toFile())) {
            boolean isOoxml = isOoxmlFormat(filePath);
            StringBuilder md = new StringBuilder();

            if (filename.endsWith(".xlsx") || (filename.endsWith(".et") && isOoxml)) {
                var workbook = new org.apache.poi.xssf.usermodel.XSSFWorkbook(fis);
                for (int s = 0; s < workbook.getNumberOfSheets(); s++) {
                    var sheet = workbook.getSheetAt(s);
                    md.append(sheetToMarkdown(sheet, sheet.getSheetName()));
                }
                workbook.close();
            } else {
                var workbook = new org.apache.poi.hssf.usermodel.HSSFWorkbook(fis);
                for (int s = 0; s < workbook.getNumberOfSheets(); s++) {
                    var sheet = workbook.getSheetAt(s);
                    md.append(hssfSheetToMarkdown(sheet, sheet.getSheetName()));
                }
                workbook.close();
            }
            return md.toString();
        } catch (Exception e) {
            log.warn("Excel转Markdown失败: {}", e.getMessage());
            return "";
        }
    }

    /**
     * CSV文件转Markdown表格
     */
    private String csvToMarkdown(Path filePath) {
        try {
            List<String> lines = Files.readAllLines(filePath, java.nio.charset.StandardCharsets.UTF_8);
            if (lines.isEmpty()) return "";

            StringBuilder md = new StringBuilder();
            md.append("### ").append(filePath.getFileName().toString()).append("\n\n");

            // 解析所有行
            List<String[]> rows = new ArrayList<>();
            int maxCol = 0;
            for (String line : lines) {
                if (line.trim().isEmpty()) continue;
                String[] cells = parseCsvLine(line);
                rows.add(cells);
                maxCol = Math.max(maxCol, cells.length);
            }

            if (rows.isEmpty()) return "";

            // 输出表格
            for (int r = 0; r < rows.size(); r++) {
                String[] cells = rows.get(r);
                md.append("|");
                for (int c = 0; c < maxCol; c++) {
                    String val = c < cells.length ? cells[c].replace("|", "\\|").replace("\n", " ") : "";
                    md.append(" ").append(val).append(" |");
                }
                md.append("\n");
                if (r == 0) {
                    md.append("|");
                    for (int c = 0; c < maxCol; c++) md.append(" --- |");
                    md.append("\n");
                }
            }
            md.append("\n");
            return md.toString();
        } catch (Exception e) {
            log.warn("CSV转Markdown失败: {}", e.getMessage());
            return "";
        }
    }

    /**
     * 解析CSV单行（支持引号包裹的字段）
     */
    private String[] parseCsvLine(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;

        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '"') {
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    current.append('"');
                    i++;
                } else {
                    inQuotes = !inQuotes;
                }
            } else if (ch == ',' && !inQuotes) {
                fields.add(current.toString().trim());
                current = new StringBuilder();
            } else {
                current.append(ch);
            }
        }
        fields.add(current.toString().trim());
        return fields.toArray(new String[0]);
    }

    private String sheetToMarkdown(org.apache.poi.xssf.usermodel.XSSFSheet sheet, String sheetName) {
        StringBuilder md = new StringBuilder();
        md.append("### ").append(sheetName).append("\n\n");

        int lastRow = sheet.getLastRowNum();
        if (lastRow < 0) return md.toString();

        int maxCol = 0;
        for (int r = 0; r <= lastRow; r++) {
            var row = sheet.getRow(r);
            if (row != null) maxCol = Math.max(maxCol, row.getLastCellNum());
        }

        for (int r = 0; r <= lastRow; r++) {
            var row = sheet.getRow(r);
            md.append("|");
            for (int c = 0; c < maxCol; c++) {
                String val = "";
                if (row != null) {
                    var cell = row.getCell(c);
                    if (cell != null) val = getCellStringValue(cell);
                }
                md.append(" ").append(val).append(" |");
            }
            md.append("\n");
            if (r == 0) {
                md.append("|");
                for (int c = 0; c < maxCol; c++) md.append(" --- |");
                md.append("\n");
            }
        }
        md.append("\n");
        return md.toString();
    }

    private String hssfSheetToMarkdown(org.apache.poi.hssf.usermodel.HSSFSheet sheet, String sheetName) {
        StringBuilder md = new StringBuilder();
        md.append("### ").append(sheetName).append("\n\n");

        int lastRow = sheet.getLastRowNum();
        if (lastRow < 0) return md.toString();

        int maxCol = 0;
        for (int r = 0; r <= lastRow; r++) {
            var row = sheet.getRow(r);
            if (row != null) maxCol = Math.max(maxCol, row.getLastCellNum());
        }

        for (int r = 0; r <= lastRow; r++) {
            var row = sheet.getRow(r);
            md.append("|");
            for (int c = 0; c < maxCol; c++) {
                String val = "";
                if (row != null) {
                    var cell = row.getCell(c);
                    if (cell != null) val = getHssfCellStringValue(cell);
                }
                md.append(" ").append(val).append(" |");
            }
            md.append("\n");
            if (r == 0) {
                md.append("|");
                for (int c = 0; c < maxCol; c++) md.append(" --- |");
                md.append("\n");
            }
        }
        md.append("\n");
        return md.toString();
    }

    private String getCellStringValue(org.apache.poi.xssf.usermodel.XSSFCell cell) {
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue().replace("|", "\\|").replace("\n", " ");
            case NUMERIC -> String.valueOf(cell.getNumericCellValue());
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            case FORMULA -> cell.getCellFormula();
            default -> "";
        };
    }

    private String getHssfCellStringValue(org.apache.poi.hssf.usermodel.HSSFCell cell) {
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue().replace("|", "\\|").replace("\n", " ");
            case NUMERIC -> String.valueOf(cell.getNumericCellValue());
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            case FORMULA -> cell.getCellFormula();
            default -> "";
        };
    }

    /**
     * VLM-OCR：将表格图片识别为 Markdown 表格
     * 优先使用远程 VLM API，失败时回退到本地 PaddleOCR-VL
     * @param imageData 图片字节数据
     * @param mimeType 图片MIME类型
     * @return Markdown 格式的表格
     */
    public String ocrTableImage(byte[] imageData, String mimeType) {
        // 1. 尝试 VLM API
        try {
            LlmConfig config = getOcrConfig();
            if (config != null) {
                log.info("表格 OCR：使用 VLM API ({})", config.getModel());
                String base64 = Base64.getEncoder().encodeToString(imageData);
                String systemPrompt = """
                        你是一个专业的表格OCR识别助手。请精确识别图片中的表格内容，并将其转换为Markdown表格格式。

                        要求：
                        1. 严格保持原表格的行列结构
                        2. 保留所有单元格中的文字内容
                        3. 使用标准Markdown表格语法（|分隔列，---分隔表头）
                        4. 如果表格跨页/被截断，只输出当前图片中可见的部分
                        5. 只输出Markdown表格，不要其他文字说明
                        """;
                String userMessage = "请将这张图片中的表格转换为Markdown格式。";

                String result = llmService.chatWithVision(config, systemPrompt, userMessage, base64, mimeType);
                String cleaned = cleanMarkdownTable(result);
                if (cleaned.contains("<table") || cleaned.contains("<tr")) {
                    cleaned = htmlToMarkdownTable(cleaned);
                }
                if (cleaned != null && !cleaned.isEmpty() && !cleaned.startsWith("[OCR失败")) {
                    return cleaned;
                }
                log.warn("表格 OCR：VLM 返回空结果，尝试本地 PaddleOCR-VL 后备");
            } else {
                log.info("表格 OCR：无可用 VLM 配置，使用本地 PaddleOCR-VL");
            }
        } catch (Exception e) {
            log.warn("表格 OCR：VLM API 调用失败 ({}), 尝试本地 PaddleOCR-VL 后备", e.getMessage());
        }

        // 2. 回退到本地 PaddleOCR-VL
        try {
            String localResult = ocrTableImageLocal(imageData, mimeType);
            if (localResult != null && !localResult.isEmpty() && !localResult.startsWith("[OCR失败")) {
                log.info("表格 OCR：本地 PaddleOCR-VL 成功");
                return localResult;
            }
        } catch (Exception e) {
            log.warn("表格 OCR：本地 PaddleOCR-VL 也失败: {}", e.getMessage());
        }

        // 3. 两者都失败
        return "[OCR失败：VLM API 不可用且本地 PaddleOCR-VL 未启动。请配置支持 vision 的 VLM 模型，或启动 parser-service (pip install paddleocr-vl)]";
    }

    /**
     * 本地 PaddleOCR-VL 表格识别（通过 parser-service）
     * @param imageData 图片字节数据
     * @param mimeType 图片MIME类型
     * @return Markdown 格式的表格
     */
    private String ocrTableImageLocal(byte[] imageData, String mimeType) throws Exception {
        String boundary = "----OCRBoundary" + System.currentTimeMillis();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();

        // 构建 multipart 请求体
        baos.write(("--" + boundary + "\r\n").getBytes());
        baos.write(("Content-Disposition: form-data; name=\"image\"; filename=\"table.png\"\r\n").getBytes());
        baos.write(("Content-Type: " + mimeType + "\r\n\r\n").getBytes());
        baos.write(imageData);
        baos.write(("\r\n--" + boundary + "--\r\n").getBytes());

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(parserServiceUrl + "/api/ocr-table"))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .timeout(Duration.ofSeconds(120))
                .POST(HttpRequest.BodyPublishers.ofByteArray(baos.toByteArray()))
                .build();

        HttpResponse<String> response = parserHttpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            String body = response.body();
            if (response.statusCode() == 503) {
                throw new RuntimeException("PaddleOCR-VL 未安装，请运行: pip install paddleocr-vl");
            }
            throw new RuntimeException("本地 OCR 服务返回错误 (HTTP " + response.statusCode() + "): "
                    + (body != null && body.length() > 200 ? body.substring(0, 200) : body));
        }

        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.JsonNode json = mapper.readTree(response.body());
        if (json.has("markdown")) {
            String md = json.get("markdown").asText();
            return cleanMarkdownTable(md);
        }
        if (json.has("error")) {
            throw new RuntimeException("本地 OCR 错误: " + json.get("error").asText());
        }
        return "";
    }

    /**
     * 合并多个 Markdown 表格片段为一个完整表格
     * 适用于：一个表格被分成多张图片的情况
     * @param markdownParts 多个 Markdown 表格片段
     * @return 合并后的完整 Markdown 表格
     */
    public String mergeMarkdownTables(List<String> markdownParts) {
        if (markdownParts == null || markdownParts.isEmpty()) return "";
        if (markdownParts.size() == 1) return markdownParts.get(0);

        try {
            LlmConfig config = llmService.getActiveChatConfig();
            if (config == null) {
                // fallback: 简单拼接
                return String.join("\n\n", markdownParts);
            }

            StringBuilder partsText = new StringBuilder();
            for (int i = 0; i < markdownParts.size(); i++) {
                partsText.append("=== 片段").append(i + 1).append(" ===\n");
                partsText.append(markdownParts.get(i)).append("\n\n");
            }

            String systemPrompt = """
                    你是一个专业的表格合并助手。以下是一个表格被分成了多个片段的Markdown表格。
                    请将这些片段合并为一个完整的Markdown表格。

                    合并规则：
                    1. 只保留一行表头（第一行和分隔行）
                    2. 将所有片段的数据行合并到一起
                    3. 确保列数一致，不对齐的单元格填空字符串
                    4. 保持数据顺序（按片段顺序拼接数据行）
                    5. 只输出合并后的Markdown表格，不要其他说明
                    """;
            String userMessage = "请合并以下表格片段为一个完整的Markdown表格：\n\n" + partsText;

            return cleanMarkdownTable(llmService.chatWithActive(systemPrompt, userMessage));
        } catch (Exception e) {
            log.warn("合并Markdown表格失败: {}", e.getMessage());
            return String.join("\n\n", markdownParts);
        }
    }

    /**
     * 清理 LLM 返回的 Markdown 表格（去除代码块标记等）
     */
    private String cleanMarkdownTable(String raw) {
        if (raw == null) return "";
        String cleaned = raw.trim();
        // 去除 ```markdown ... ``` 包裹
        if (cleaned.startsWith("```")) {
            cleaned = cleaned.replaceAll("^```\\w*\\n?", "").replaceAll("\\n?```$", "");
        }
        return cleaned.trim();
    }

    /**
     * 将 HTML 表格转换为 Markdown 表格格式
     * 用于处理 OCR 返回 HTML 表格的情况，以及历史数据中存储的 HTML 表格
     * @param htmlTable HTML 表格字符串（可包含 &lt;table&gt; 标签）
     * @return Markdown 格式的表格
     */
    public String htmlTableToMarkdown(String htmlTable) {
        if (htmlTable == null || htmlTable.isEmpty()) return "";

        // 如果已经是 Markdown 格式（包含 | 分隔），直接返回
        String trimmed = htmlTable.trim();
        if (!trimmed.toLowerCase().contains("<table") && trimmed.contains("|")) {
            return trimmed;
        }

        try {
            // 提取 <table>...</table> 部分
            java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
                    "<table[^>]*>(.*?)</table>", java.util.regex.Pattern.DOTALL | java.util.regex.Pattern.CASE_INSENSITIVE);
            java.util.regex.Matcher matcher = pattern.matcher(htmlTable);

            List<List<String>> rows = new ArrayList<>();
            int maxCols = 0;

            while (matcher.find()) {
                String tableContent = matcher.group(1);
                List<List<String>> tableRows = parseHtmlTableRows(tableContent);
                rows.addAll(tableRows);
                for (List<String> row : tableRows) {
                    maxCols = Math.max(maxCols, row.size());
                }
            }

            if (rows.isEmpty() || maxCols == 0) return "";

            // 补齐列数
            for (List<String> row : rows) {
                while (row.size() < maxCols) {
                    row.add("");
                }
            }

            // 构建 Markdown
            StringBuilder md = new StringBuilder();
            for (int r = 0; r < rows.size(); r++) {
                List<String> row = rows.get(r);
                md.append("|");
                for (String cell : row) {
                    String cleaned = cell.replace("|", "\\|").replace("\n", " ").trim();
                    md.append(" ").append(cleaned).append(" |");
                }
                md.append("\n");
                // 在第一行后添加分隔行
                if (r == 0) {
                    md.append("|");
                    for (int c = 0; c < maxCols; c++) {
                        md.append(" --- |");
                    }
                    md.append("\n");
                }
            }
            return md.toString().trim();
        } catch (Exception e) {
            log.warn("HTML表格转Markdown失败: {}", e.getMessage());
            return htmlTable; // 转换失败时返回原始内容
        }
    }

    /**
     * 解析 HTML 表格中的行和单元格
     */
    private List<List<String>> parseHtmlTableRows(String tableContent) {
        List<List<String>> rows = new ArrayList<>();
        java.util.regex.Pattern trPattern = java.util.regex.Pattern.compile(
                "<tr[^>]*>(.*?)</tr>", java.util.regex.Pattern.DOTALL | java.util.regex.Pattern.CASE_INSENSITIVE);
        java.util.regex.Pattern tdPattern = java.util.regex.Pattern.compile(
                "<t[dh][^>]*>(.*?)</t[dh]>", java.util.regex.Pattern.DOTALL | java.util.regex.Pattern.CASE_INSENSITIVE);

        java.util.regex.Matcher trMatcher = trPattern.matcher(tableContent);
        while (trMatcher.find()) {
            String rowContent = trMatcher.group(1);
            List<String> cells = new ArrayList<>();
            java.util.regex.Matcher tdMatcher = tdPattern.matcher(rowContent);
            while (tdMatcher.find()) {
                String cellContent = tdMatcher.group(1);
                // 去除HTML标签，保留文本
                cellContent = cellContent.replaceAll("<[^>]+>", "").trim();
                // 解码HTML实体
                cellContent = cellContent.replace("&amp;", "&")
                        .replace("&lt;", "<")
                        .replace("&gt;", ">")
                        .replace("&nbsp;", " ")
                        .replace("&quot;", "\"");
                cells.add(cellContent);
            }
            if (!cells.isEmpty()) {
                rows.add(cells);
            }
        }
        return rows;
    }

    /**
     * 将 HTML 表格转换为 Markdown 表格
     * 当 VLM/OCR 返回 HTML 格式的表格时，自动转换为 Markdown 格式
     */
    String htmlToMarkdownTable(String html) {
        try {
            List<List<String>> rows = new ArrayList<>();
            // 提取所有 <tr> 行
            String[] trParts = html.split("(?i)<tr[^>]*>");
            for (String trPart : trParts) {
                // 匹配 <th> 和 <td> 单元格
                List<String> cells = new ArrayList<>();
                String[] tdParts = trPart.split("(?i)<t[dh][^>]*>");
                for (String tdPart : tdParts) {
                    // 去掉闭合标签和多余空白
                    String cell = tdPart.replaceAll("(?i)</t[dh]>", "")
                            .replaceAll("<[^>]+>", "")
                            .replaceAll("\\s+", " ")
                            .trim();
                    if (!cell.isEmpty()) {
                        cells.add(cell);
                    }
                }
                if (!cells.isEmpty()) {
                    rows.add(cells);
                }
            }

            if (rows.isEmpty()) return html;

            // 计算最大列数
            int maxCols = rows.stream().mapToInt(List::size).max().orElse(0);
            if (maxCols == 0) return html;

            // 补齐列数
            for (List<String> row : rows) {
                while (row.size() < maxCols) row.add("");
            }

            // 构建 Markdown 表格
            StringBuilder sb = new StringBuilder();
            // 表头
            sb.append("| ");
            for (String cell : rows.get(0)) {
                sb.append(cell).append(" | ");
            }
            sb.setLength(sb.length() - 1); // 去掉多余空格
            sb.append("\n");
            // 分隔行
            sb.append("| ");
            for (int i = 0; i < maxCols; i++) {
                sb.append("--- | ");
            }
            sb.setLength(sb.length() - 1);
            sb.append("\n");
            // 数据行
            for (int i = 1; i < rows.size(); i++) {
                sb.append("| ");
                for (String cell : rows.get(i)) {
                    sb.append(cell).append(" | ");
                }
                sb.setLength(sb.length() - 1);
                sb.append("\n");
            }
            return sb.toString().trim();
        } catch (Exception e) {
            log.warn("HTML表格转Markdown失败: {}", e.getMessage());
            return html;
        }
    }

    /**
     * 处理表格图片：保存图片 → OCR识别 → 创建表格条目
     */
    public KnowledgeEntry processTableImage(byte[] imageData, String filename,
                                              String sourceOrigin, String library, Long docId, Long projectId) throws Exception {
        String ext = getExtension(filename);
        String mime = getMimeType(ext);

        // 保存图片
        String mediaPath = saveImage(imageData, docId, 0, ext);

        // OCR 识别表格
        String tableMarkdown = ocrTableImage(imageData, mime);

        // 确保 table_markdown 存储为 standard Markdown
        if (tableMarkdown != null && tableMarkdown.toLowerCase().contains("<table")) {
            tableMarkdown = htmlTableToMarkdown(tableMarkdown);
        }

        // 同时生成 caption 作为描述
        String caption = generateCaption(imageData, mime);

        // 优先参考现有词条：查找该项目下名称匹配 of 现有概念/实体词条
        if (projectId != null) {
            List<KnowledgeEntry> existing = knowledgeEntryMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<KnowledgeEntry>()
                    .eq(KnowledgeEntry::getProjectId, projectId)
                    .ne(KnowledgeEntry::getEntryType, "image")
                    .ne(KnowledgeEntry::getEntryType, "table")
            );
            for (KnowledgeEntry e : existing) {
                // 如果文件名包含词条标题，或 caption/tableMarkdown 包含词条标题，则优先关联该词条
                if (filename.contains(e.getTitle()) || 
                    (caption != null && caption.contains(e.getTitle())) ||
                    (tableMarkdown != null && tableMarkdown.contains(e.getTitle()))) {
                    
                    log.info("【图片表格处理】优先匹配并关联到现有词条: {}", e.getTitle());
                    if (e.getContent() == null || e.getContent().isEmpty()) {
                        e.setContent("**关联表格数据**：\n" + tableMarkdown);
                    } else if (tableMarkdown != null && !e.getContent().contains(tableMarkdown)) {
                        e.setContent(e.getContent() + "\n\n**关联表格数据**：\n" + tableMarkdown);
                    }
                    knowledgeEntryMapper.updateById(e);
                    vectorSearchService.indexEntry(e); // 重新建立向量索引
                    break;
                }
            }
        }

        // 无法关联现有词条时，再创建独立的表格词条
        KnowledgeEntry entry = new KnowledgeEntry();
        entry.setTitle(filename);
        entry.setEntryType("table");
        entry.setEntryLibrary(library);
        entry.setDocumentId(docId);
        entry.setSourceName(filename);
        entry.setContent(caption);
        entry.setKeywords("表格,OCR,图片表格");
        entry.setStatus(isAutoApprove() ? "approved" : "pending");
        entry.setConfidence(0.85);
        entry.setCreatedAt(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        entry.setMediaType("table");
        entry.setMediaPath(mediaPath);
        entry.setTableMarkdown(tableMarkdown);
        entry.setSourceOrigin(sourceOrigin);
        entry.setProjectId(projectId);

        knowledgeEntryMapper.insert(entry);
        vectorSearchService.indexEntry(entry);
        return entry;
    }

    /**
     * 处理独立上传的图片（非文档中提取的，优先关联到现有词条）
     */
    public KnowledgeEntry processStandaloneImage(byte[] imageData, String filename,
                                                   String sourceOrigin, String library, Long docId, Long projectId) throws Exception {
        String ext = getExtension(filename);
        String mime = getMimeType(ext);

        // 保存图片
        String mediaPath = saveImage(imageData, docId, 0, ext);

        // VLM生成caption
        String caption = generateCaption(imageData, mime);

        // 优先参考现有词条：查找该项目下名称匹配的现有概念/实体词条
        if (projectId != null) {
            List<KnowledgeEntry> existing = knowledgeEntryMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<KnowledgeEntry>()
                    .eq(KnowledgeEntry::getProjectId, projectId)
                    .ne(KnowledgeEntry::getEntryType, "image")
                    .ne(KnowledgeEntry::getEntryType, "table")
            );
            for (KnowledgeEntry e : existing) {
                // 如果文件名包含词条标题，或 caption 包含词条标题，则优先关联该词条
                if (filename.contains(e.getTitle()) || (caption != null && caption.contains(e.getTitle()))) {
                    log.info("【图片处理】优先匹配并关联到现有词条: {}", e.getTitle());
                    if (e.getContent() == null || e.getContent().isEmpty()) {
                        e.setContent("**关联图片描述**：\n" + caption);
                    } else if (caption != null && !e.getContent().contains(caption)) {
                        e.setContent(e.getContent() + "\n\n**关联图片描述**：\n" + caption);
                    }
                    knowledgeEntryMapper.updateById(e);
                    vectorSearchService.indexEntry(e); // 重新建立向量索引
                    break;
                }
            }
        }

        // 无法关联现有词条时，再创建独立的图片词条
        KnowledgeEntry entry = new KnowledgeEntry();
        entry.setTitle(filename);
        entry.setEntryType("image");
        entry.setEntryLibrary(library);
        entry.setDocumentId(docId);
        entry.setSourceName(filename);
        entry.setContent(caption);
        entry.setKeywords("图片,图表,图示");
        entry.setStatus(isAutoApprove() ? "approved" : "pending");
        entry.setConfidence(0.8);
        entry.setCreatedAt(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        entry.setMediaType("image");
        entry.setMediaPath(mediaPath);
        entry.setSourceOrigin(sourceOrigin);
        entry.setProjectId(projectId);

        knowledgeEntryMapper.insert(entry);
        vectorSearchService.indexEntry(entry);
        return entry;
    }

    private boolean isAutoApprove() {
        com.intelligence.platform.entity.Setting s = settingMapper.selectById("auto_approve_entries");
        return s != null && "true".equals(s.getValue());
    }

    // === 辅助方法 ===

    private boolean isImageFile(String name) {
        String lower = name.toLowerCase();
        return lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                || lower.endsWith(".gif") || lower.endsWith(".webp") || lower.endsWith(".bmp");
    }

    private boolean isValidImage(byte[] data) {
        if (data == null || data.length < 100) return false;
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(data));
            if (img == null) return false;
            return img.getWidth() >= MIN_IMAGE_SIZE && img.getHeight() >= MIN_IMAGE_SIZE;
        } catch (Exception e) {
            return false;
        }
    }

    private String getExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(dot + 1).toLowerCase() : "png";
    }

    private String getMimeType(String ext) {
        return switch (ext) {
            case "jpg", "jpeg" -> "image/jpeg";
            case "png" -> "image/png";
            case "gif" -> "image/gif";
            case "webp" -> "image/webp";
            case "bmp" -> "image/bmp";
            case "svg" -> "image/svg+xml";
            default -> "image/png";
        };
    }

    private boolean isOoxmlFormat(Path path) {
        try (var is = Files.newInputStream(path)) {
            byte[] header = new byte[4];
            if (is.read(header) == 4) {
                return header[0] == 0x50 && header[1] == 0x4B;
            }
        } catch (Exception ignored) {}
        return false;
    }

    /**
     * 提取的图片数据结构
     */
    public record ExtractedImage(int index, String extension, String mimeType, byte[] data, Integer page) {}
}
