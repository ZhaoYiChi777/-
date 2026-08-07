package com.intelligence.platform.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelligence.platform.entity.Document;
import com.intelligence.platform.entity.KnowledgeEntry;
import com.intelligence.platform.entity.Setting;
import com.intelligence.platform.mapper.DocumentMapper;
import com.intelligence.platform.mapper.KnowledgeEntryMapper;
import com.intelligence.platform.mapper.SettingMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 文档解析与LLM词条抽取服务
 * 参考 llm_wiki 的 ingest.ts（文档摄取流程）
 * 上传文档 → 解析文本 → LLM抽取结构化词条 → 存入knowledge_entries
 * 支持多文件类型：PDF / Word / Excel / PPT / TXT / Markdown / 图片OCR
 */
@Service
public class DocumentParseService {

    private static final Logger log = LoggerFactory.getLogger(DocumentParseService.class);

    @Autowired
    private LlmService llmService;
    @Autowired
    private KnowledgeEntryMapper knowledgeEntryMapper;
    @Autowired
    private DocumentMapper documentMapper;
    @Autowired
    private SettingMapper settingMapper;
    @Autowired
    private VectorSearchService vectorSearchService;
    @Autowired
    private ImageService imageService;

    @Autowired
    private MinerUClient minerUClient;

    @Autowired
    private LiteParseClient liteParseClient;

    private boolean isAutoApprove() {
        Setting s = settingMapper.selectById("auto_approve_entries");
        return s != null && "true".equals(s.getValue());
    }

    /**
     * 从文本中抽取知识词条（支持长文档智能分块）
     * 参考 llm_wiki 的 ingest.ts：按段落/句子边界切分，而非简单字符计数
     * - 短文档（<12000字符）：直接抽取
     * - 长文档：按段落边界切分为30000字符左右的块，逐块抽取后合并
     */
    private List<KnowledgeEntry> extractEntriesFromText(String text, Document doc, String library) throws Exception {
        List<KnowledgeEntry> allEntries = new ArrayList<>();

        // 智能分块
        List<String> chunks = chunkText(text, 12000, 30000);
        int totalChunks = chunks.size();
        log.info("Document '{}': {} chars split into {} chunks", doc.getTitle(), text.length(), totalChunks);

        String systemPrompt = """
                你是一个专业的知识词条抽取助手（参考 GitHub 开源项目 llm_wiki 的知识定义）。
                请从以下文档文本中抽取高价值的知识词条。

                【词条类型要求】：
                词条类型必须是以下之一，禁止使用其他类型：
                - concept (核心概念、定义、技术术语)
                - entity (机构、平台、特定产品或实体名称)
                - thesis (核心论点、学术观点、主要立论)
                - methodology (研究方法、分析框架、方法论)
                - finding (核心发现、重要结论、事实数据总结)
                - comparison (横向对比、多维度对比分析)
                - synthesis (综合论述、跨领域融合分析)

                【输出 JSON 字段要求】：
                每个词条包含以下属性，必须以 JSON 数组格式返回：
                1. title: 词条标题，应为简练的专有名词或论点句
                2. type: 词条类型（即上面要求的值之一，例如 concept 或 finding）
                3. description: 一句话的简短描述或核心释义
                4. tags: 标签列表，JSON 字符串数组，例如 ["社区医疗", "慢病管理"]
                5. related: 关联的其他词条标题列表，JSON 字符串数组，例如 ["分级诊疗", "基层医保"]
                6. content: 详细的正文内容，必须为结构化的 Markdown 格式（可以包含二级标题、列表、粗体、甚至数学公式，用于全面阐述该词条）

                【返回格式限制】：
                只返回标准 JSON 数组，严禁包含 ```json 等任何 Markdown 标记或多余解释性文字。
                示例：
                [{"title": "分级诊疗", "type": "concept", "description": "由基层到大医院的诊疗分流机制", "tags": ["政策", "基层医疗"], "related": ["社区医院", "医保控费"], "content": "分级诊疗的实施包含以下核心要点：\\n1. **首诊在基层**...\\n2. **双向转诊**..."}]
                """;

        // 智能标签：查询已有词条作为LLM参考上下文，优先复用已有标签体系
        String existingContext = buildExistingEntriesContext(doc.getProjectId(), library);
        String effectivePrompt = systemPrompt + existingContext;

        for (int i = 0; i < totalChunks; i++) {
            String chunk = chunks.get(i);
            String userPrompt = totalChunks > 1
                    ? String.format("请从以下文本中抽取知识词条（第 %d/%d 块，共 %d 字符）：\n\n%s",
                    i + 1, totalChunks, chunk.length(), chunk)
                    : "请从以下文本中抽取知识词条：\n\n" + chunk;

            try {
                String response = llmService.chatWithExtract(effectivePrompt, userPrompt);
                List<KnowledgeEntry> chunkEntries = parseEntriesFromResponse(response, doc, library);
                allEntries.addAll(chunkEntries);
                log.info("Chunk {}/{}: extracted {} entries", i + 1, totalChunks, chunkEntries.size());
            } catch (Exception e) {
                log.warn("Chunk {}/{} extraction failed: {} - {}", i + 1, totalChunks, e.getClass().getName(), e.getMessage());
                log.warn("Extraction stack trace:", e);
            }
        }

        return allEntries;
    }

    /**
     * 智能文本分块（参考 llm_wiki splitTextIntoChunks）
     * 按段落边界 → 句子边界 → 单词边界 逐级尝试，避免截断在中间
     * @param text 原始文本
     * @param minChunkSize 最短文档不需要分块的阈值
     * @param targetChunkSize 目标块大小
     * @return 分块列表
     */
    private List<String> chunkText(String text, int minChunkSize, int targetChunkSize) {
        if (text.length() <= minChunkSize) {
            return List.of(text);
        }

        List<String> chunks = new ArrayList<>();
        String[] paragraphs = text.split("\n\n+"); // 按双换行分段落
        StringBuilder current = new StringBuilder();

        for (String para : paragraphs) {
            // 如果当前段落本身很长，先放入之前的内容，再分块处理这个段落
            if (para.length() > targetChunkSize) {
                if (!current.isEmpty()) {
                    chunks.add(current.toString());
                    current = new StringBuilder();
                }
                // 按单换行（句子边界）拆分超长段落
                String[] sentences = para.split("\n");
                for (String sentence : sentences) {
                    if (current.length() + sentence.length() > targetChunkSize && !current.isEmpty()) {
                        chunks.add(current.toString());
                        current = new StringBuilder();
                    }
                    if (!current.isEmpty()) current.append("\n");
                    current.append(sentence);
                }
            } else {
                if (current.length() + para.length() > targetChunkSize && !current.isEmpty()) {
                    chunks.add(current.toString());
                    current = new StringBuilder();
                }
                if (!current.isEmpty()) current.append("\n\n");
                current.append(para);
            }
        }

        if (!current.isEmpty()) {
            chunks.add(current.toString());
        }

        return chunks;
    }

    @Value("${upload.dir:../uploads}")
    private String uploadDir;

    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * 解析文档并使用LLM抽取知识词条
     * @param docId 文档ID
     * @return 抽取的词条列表
     */
    public List<KnowledgeEntry> parseAndExtract(Long docId) throws Exception {
        return parseAndExtract(docId, null);
    }

    /**
     * 解析文档并使用LLM抽取知识词条（可覆盖目标库）
     * @param docId 文档ID
     * @param overrideLibrary 可选：覆盖由docType推断的library（如用户明确选择了"chart"）
     * @return 抽取的词条列表
     */
    public List<KnowledgeEntry> parseAndExtract(Long docId, String overrideLibrary) throws Exception {
        Document doc = documentMapper.selectById(docId);
        if (doc == null) throw new RuntimeException("文档不存在: " + docId);

        List<KnowledgeEntry> entries = new ArrayList<>();
        // 从标题提取扩展名，若标题无扩展名则回退到文件路径
        String extension = getExtension(doc.getTitle());
        if (extension.isEmpty() && doc.getFilePath() != null) {
            extension = getExtension(Path.of(doc.getFilePath()).getFileName().toString());
        }

        String library;
        String fileTypeHint = null;
        if ("image".equals(overrideLibrary) || "table".equals(overrideLibrary)) {
            // fileType hint, not library - get library from docType
            library = mapDocTypeToLibrary(doc.getDocType());
            fileTypeHint = overrideLibrary;
        } else {
            library = (overrideLibrary != null && !overrideLibrary.isEmpty())
                    ? overrideLibrary : mapDocTypeToLibrary(doc.getDocType());
        }

        String sourceOrigin = buildSourceOrigin(doc);

        // 特殊处理：CSV/Excel文件 → 直接转为Markdown表格
        if (List.of("csv", "xls", "xlsx", "et").contains(extension)) {
            String markdown = imageService.excelToMarkdown(Path.of(doc.getFilePath()));
            KnowledgeEntry entry = new KnowledgeEntry();
            entry.setTitle(doc.getTitle());
            entry.setEntryType("table");
            entry.setEntryLibrary(library);
            entry.setDocumentId(doc.getId());
            entry.setSourceName(doc.getTitle());
            entry.setContent("表格数据（" + extension.toUpperCase() + "格式）");
            entry.setKeywords("表格," + extension.toUpperCase());
            entry.setStatus(isAutoApprove() ? "approved" : "pending");
            entry.setConfidence(0.9);
            entry.setCreatedAt(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
            entry.setMediaType("table");
            entry.setTableMarkdown(markdown);
            entry.setSourceOrigin(sourceOrigin);
            entry.setProjectId(doc.getProjectId());
            
            knowledgeEntryMapper.insert(entry);
            vectorSearchService.indexEntry(entry);
            entries.add(entry);
            
            doc.setStatus("extracted");
            documentMapper.updateById(doc);
            return entries;
        }

        // 特殊处理：独立图片文件
        if (List.of("jpg", "jpeg", "png", "gif", "bmp", "webp", "tiff", "tif", "svg").contains(extension)) {
            byte[] imgData = Files.readAllBytes(Path.of(doc.getFilePath()));
            boolean isTable = "table".equals(fileTypeHint);
            KnowledgeEntry entry;
            if (isTable) {
                // 使用VLM进行OCR表格识别
                entry = imageService.processTableImage(imgData, doc.getTitle(), sourceOrigin, library, doc.getId(), doc.getProjectId());
            } else {
                entry = imageService.processStandaloneImage(imgData, doc.getTitle(), sourceOrigin, library, doc.getId(), doc.getProjectId());
            }
            entries.add(entry);
            doc.setStatus("extracted");
            documentMapper.updateById(doc);
            return entries;
        }

        // 1. 读取文档内容（简化版，实际应调用Apache Tika / MinerU等）
        String text = readDocumentText(doc);
        if (text == null || text.isEmpty()) {
            throw new RuntimeException("无法读取文档内容: " + doc.getTitle());
        }

        // 2. 图表库智能标签：从文本中提取标签并应用到关联的图表词条
        if ("chart".equals(library)) {
            String smartTags = extractSmartTags(text, doc.getProjectId());
            if (smartTags != null && !smartTags.isEmpty()) {
                // 将智能标签存储到文档的keywords字段
                doc.setKeywords(smartTags);
                log.info("Chart document '{}': extracted smart tags: {}", doc.getTitle(), smartTags);
                // 将标签应用到该文档关联的图表词条
                updateChartTagsForDocument(doc.getId(), smartTags, doc.getProjectId());
            }
        }

        // 3. 调用LLM抽取词条（支持长文档分块处理）
        // 图表库不应产生文本词条，只处理图片/表格
        if (!"chart".equals(library)) {
            List<KnowledgeEntry> textEntries = extractEntriesFromText(text, doc, library);
            entries.addAll(textEntries);

            // 4. 批量入库并索引到向量数据库
            for (KnowledgeEntry entry : textEntries) {
                knowledgeEntryMapper.insert(entry);
                // 自动生成嵌入向量并加入索引
                vectorSearchService.indexEntry(entry);
            }
        }

        // 更新文档状态
        doc.setStatus("extracted");
        documentMapper.updateById(doc);

        return entries;
    }

    /**
     * 从文档中提取嵌入图片
     */
    private List<ImageService.ExtractedImage> extractDocumentImages(Document doc) {
        if (doc.getFilePath() == null) return List.of();
        Path path = Path.of(doc.getFilePath());
        if (!Files.exists(path)) return List.of();

        String extension = getExtension(doc.getTitle());
        return switch (extension) {
            case "pptx", "dps" -> {
                if (isOoxmlFormat(path)) {
                    yield imageService.extractFromOoxml(path);
                } else {
                    yield imageService.extractFromPpt(path);
                }
            }
            case "ppt" -> imageService.extractFromPpt(path);
            case "docx", "wps" -> {
                if (isOoxmlFormat(path)) {
                    yield imageService.extractFromOoxml(path);
                } else {
                    yield List.of();
                }
            }
            default -> List.of();
        };
    }

    /**
     * 构建来源信息（优先使用用户填写的sourceOrigin，否则自动从文档元信息提取）
     */
    private String buildSourceOrigin(Document doc) {
        // 优先使用用户手动填写的来源
        if (doc.getSourceOrigin() != null && !doc.getSourceOrigin().isEmpty()) {
            return doc.getSourceOrigin();
        }
        // 自动提取：文件名 + 分类
        StringBuilder origin = new StringBuilder();
        if (doc.getTitle() != null) origin.append(doc.getTitle());
        if (doc.getCategoryL1() != null && !doc.getCategoryL1().isEmpty()) {
            origin.append(" [").append(doc.getCategoryL1()).append("]");
        }
        return origin.toString();
    }

    /**
     * 读取文档文本内容（支持多文件类型，参考 llm_wiki ingest.ts）
     *
     * 解析策略（优先级从高到低）：
     * 1. MinerU 远程 API：PDF/DOCX/PPTX/HTML/图片 → 高质量 Markdown
     * 2. 本地解析器（fallback）：Apache POI / PDFBox 等
     * 3. 纯文本读取：TXT/MD/CSV
     */
    private String readDocumentText(Document doc) {
        try {
            if (doc.getFilePath() != null) {
                Path path = Path.of(doc.getFilePath());
                if (Files.exists(path)) {
                    String filename = path.getFileName().toString();
                    String extension = getExtension(filename);

                    // 纯文本类直接读取，不需要 MinerU
                    if (isPlainTextExtension(extension)) {
                        return Files.readString(path);
                    }

                    // LiteParse 本地 CLI 解析（优先于 MinerU 远程 API）
                    if (liteParseClient.isAvailable() && liteParseClient.isSupported(extension)) {
                        try {
                            log.info("Using LiteParse CLI to parse: {} ({})", filename, extension);
                            String liteResult = liteParseClient.parse(path);
                            if (liteResult != null && !liteResult.isEmpty() && !liteResult.startsWith("[") ) {
                                log.info("LiteParse parsed {} successfully: {} chars", filename, liteResult.length());
                                return liteResult;
                            }
                            log.warn("LiteParse returned empty or error result for {}, falling back", filename);
                        } catch (Exception e) {
                            log.warn("LiteParse failed for {} ({}), falling back to MinerU/local parser",
                                    filename, e.getMessage());
                        }
                    }

                    // MinerU 远程 API 解析复杂文档（PDF/DOCX/PPTX/HTML/图片）
                    if (minerUClient.isAvailable()) {
                        if (isMinerUSupported(extension)) {
                            String modelVersion = "html".equals(extension) || "htm".equals(extension)
                                    ? "MinerU-HTML" : "pipeline";
                            log.info("Using MinerU remote API to parse: {} ({})", filename, extension);

                            byte[] fileData = Files.readAllBytes(path);
                            MinerUClient.ParseResult result = minerUClient.parseByUpload(
                                    filename, fileData, modelVersion);
                            if (result.isSuccess()) {
                                log.info("MinerU parsed {} successfully: {} chars", filename, result.markdown().length());
                                return result.markdown();
                            }
                            log.warn("MinerU failed for {} ({}), falling back to local parser",
                                    filename, result.title());
                        }
                    }

                    // 本地解析器（fallback）
                    return switch (extension) {
                        case "pdf" -> parsePdf(path);
                        case "html", "htm" -> parseHtml(path);
                        case "doc", "docx", "wps" -> parseWord(path);
                        case "xls", "xlsx", "et" -> parseExcel(path);
                        case "ppt", "pptx", "dps" -> parsePpt(path);
                        case "odt" -> parseOdt(path);
                        case "ods" -> parseOds(path);
                        case "odp" -> parseOdp(path);
                        case "jpg", "jpeg", "png", "gif", "bmp", "webp",
                             "tiff", "tif", "svg", "ico", "heic", "heif", "avif" -> parseImage(path);
                        default -> {
                            try {
                                yield Files.readString(path);
                            } catch (Exception e) {
                                yield "[无法解析的文件类型: " + extension + "]";
                            }
                        }
                    };
                }
            }
            // 如果没有文件，返回标题和元信息
            StringBuilder sb = new StringBuilder();
            sb.append("标题: ").append(doc.getTitle()).append("\n");
            if (doc.getCategoryL1() != null) sb.append("分类: ").append(doc.getCategoryL1()).append("\n");
            if (doc.getKeywords() != null) sb.append("关键词: ").append(doc.getKeywords()).append("\n");
            if (doc.getMetaInfo() != null) sb.append("元信息: ").append(doc.getMetaInfo()).append("\n");
            return sb.toString();
        } catch (IOException e) {
            return "标题: " + doc.getTitle();
        }
    }

    /**
     * 纯文本格式（直接读取，不需要解析器）
     */
    private boolean isPlainTextExtension(String ext) {
        return "txt".equals(ext) || "md".equals(ext) || "markdown".equals(ext)
                || "csv".equals(ext) || "rtf".equals(ext) || "json".equals(ext);
    }

    /**
     * MinerU 远程 API 支持的格式
     */
    private boolean isMinerUSupported(String ext) {
        return "pdf".equals(ext) || "docx".equals(ext) || "doc".equals(ext)
                || "pptx".equals(ext) || "ppt".equals(ext)
                || "html".equals(ext) || "htm".equals(ext)
                || "jpg".equals(ext) || "jpeg".equals(ext) || "png".equals(ext)
                || "gif".equals(ext) || "bmp".equals(ext) || "webp".equals(ext)
                || "tiff".equals(ext) || "tif".equals(ext);
    }

    /**
     * 解析 HTML 文件（fallback，MinerU 优先）
     */
    private String parseHtml(Path path) {
        try {
            String html = Files.readString(path);
            String text = html.replaceAll("<script[^>]*>.*?</script>", "")
                    .replaceAll("<style[^>]*>.*?</style>", "")
                    .replaceAll("<[^>]+>", " ")
                    .replaceAll("\\s+", " ")
                    .trim();
            return text.isEmpty() ? "[HTML文件无可提取文本]" : text;
        } catch (Exception e) {
            return "[HTML解析失败: " + e.getMessage() + "]";
        }
    }

    /**
     * 获取文件扩展名
     */
    private String getExtension(String filename) {
        if (filename == null) return "";
        int lastDot = filename.lastIndexOf('.');
        return lastDot > 0 ? filename.substring(lastDot + 1).toLowerCase() : "";
    }

    /**
     * 解析PDF文件
     */
    private String parsePdf(Path path) {
        try {
            // 使用Apache PDFBox解析PDF
            org.apache.pdfbox.pdmodel.PDDocument document = org.apache.pdfbox.Loader.loadPDF(path.toFile());
            org.apache.pdfbox.text.PDFTextStripper stripper = new org.apache.pdfbox.text.PDFTextStripper();
            String text = stripper.getText(document);
            document.close();

            if (text == null || text.trim().isEmpty()) {
                return "[PDF文件无可提取文本，大小: " + Files.size(path) + " bytes]";
            }
            return text;
        } catch (Exception e) {
            return "[PDF解析失败: " + e.getMessage() + "]";
        }
    }

    /**
     * 解析Word文件（支持.doc/.docx/.wps格式）
     * WPS文字格式与Word兼容，使用相同解析器
     */
    private String parseWord(Path path) {
        try (java.io.FileInputStream fis = new java.io.FileInputStream(path.toFile())) {
            String filename = path.getFileName().toString().toLowerCase();
            // 检测文件内容判断是OOXML还是OLE2格式
            boolean isOoxml = isOoxmlFormat(path);

            if (filename.endsWith(".docx") || (filename.endsWith(".wps") && isOoxml)) {
                // 使用XWPFDocument解析.docx或新版.wps
                org.apache.poi.xwpf.usermodel.XWPFDocument document =
                    new org.apache.poi.xwpf.usermodel.XWPFDocument(fis);
                org.apache.poi.xwpf.extractor.XWPFWordExtractor extractor =
                    new org.apache.poi.xwpf.extractor.XWPFWordExtractor(document);
                String text = extractor.getText();
                extractor.close();
                document.close();
                return text;
            } else {
                // 使用HWPFDocument解析.doc或旧版.wps
                org.apache.poi.hwpf.HWPFDocument document =
                    new org.apache.poi.hwpf.HWPFDocument(fis);
                org.apache.poi.hwpf.extractor.WordExtractor extractor =
                    new org.apache.poi.hwpf.extractor.WordExtractor(document);
                String text = extractor.getText();
                extractor.close();
                document.close();
                return text;
            }
        } catch (Exception e) {
            return "[Word解析失败: " + e.getMessage() + "]";
        }
    }

    /**
     * 检测文件是否为OOXML格式（通过Magic Bytes）
     */
    private boolean isOoxmlFormat(Path path) {
        try (java.io.InputStream is = java.nio.file.Files.newInputStream(path)) {
            byte[] header = new byte[4];
            if (is.read(header) == 4) {
                // OOXML (ZIP) 格式以 PK 开头 (0x50 0x4B)
                return header[0] == 0x50 && header[1] == 0x4B;
            }
        } catch (Exception e) {
            // 忽略异常，默认使用旧格式解析
        }
        return false;
    }

    /**
     * 解析Excel文件（支持.xls/.xlsx/.et格式）
     * WPS表格格式与Excel兼容，使用相同解析器
     */
    private String parseExcel(Path path) {
        try (java.io.FileInputStream fis = new java.io.FileInputStream(path.toFile())) {
            String filename = path.getFileName().toString().toLowerCase();
            // 检测文件内容判断是OOXML还是OLE2格式
            boolean isOoxml = isOoxmlFormat(path);
            StringBuilder text = new StringBuilder();

            if (filename.endsWith(".xlsx") || (filename.endsWith(".et") && isOoxml)) {
                // 使用XSSFWorkbook解析.xlsx或新版.et
                org.apache.poi.xssf.usermodel.XSSFWorkbook workbook =
                    new org.apache.poi.xssf.usermodel.XSSFWorkbook(fis);
                org.apache.poi.xssf.extractor.XSSFExcelExtractor extractor =
                    new org.apache.poi.xssf.extractor.XSSFExcelExtractor(workbook);
                text.append(extractor.getText());
                workbook.close();
            } else {
                // 使用HSSFWorkbook解析.xls或旧版.et
                org.apache.poi.hssf.usermodel.HSSFWorkbook workbook =
                    new org.apache.poi.hssf.usermodel.HSSFWorkbook(fis);
                org.apache.poi.hssf.extractor.ExcelExtractor extractor =
                    new org.apache.poi.hssf.extractor.ExcelExtractor(workbook);
                text.append(extractor.getText());
                workbook.close();
            }

            return text.toString();
        } catch (Exception e) {
            return "[Excel解析失败: " + e.getMessage() + "]";
        }
    }

    /**
     * 解析PPT文件（支持.ppt/.pptx/.dps格式）
     * WPS演示格式与PowerPoint兼容，使用相同解析器
     */
    private String parsePpt(Path path) {
        try (java.io.FileInputStream fis = new java.io.FileInputStream(path.toFile())) {
            String filename = path.getFileName().toString().toLowerCase();
            // 检测文件内容判断是OOXML还是OLE2格式
            boolean isOoxml = isOoxmlFormat(path);

            if (filename.endsWith(".pptx") || (filename.endsWith(".dps") && isOoxml)) {
                // 使用XMLSlideShow解析.pptx或新版.dps
                org.apache.poi.xslf.usermodel.XMLSlideShow ppt =
                    new org.apache.poi.xslf.usermodel.XMLSlideShow(fis);
                org.apache.poi.xslf.extractor.XSLFExtractor extractor =
                    new org.apache.poi.xslf.extractor.XSLFExtractor(ppt);
                String text = extractor.getText();
                ppt.close();
                return text;
            } else {
                // 使用HSLFSlideShow解析.ppt或旧版.dps
                org.apache.poi.hslf.usermodel.HSLFSlideShow ppt =
                    new org.apache.poi.hslf.usermodel.HSLFSlideShow(fis);
                StringBuilder text = new StringBuilder();
                for (org.apache.poi.hslf.usermodel.HSLFSlide slide : ppt.getSlides()) {
                    for (org.apache.poi.hslf.usermodel.HSLFShape shape : slide.getShapes()) {
                        if (shape instanceof org.apache.poi.hslf.usermodel.HSLFTextShape) {
                            text.append(((org.apache.poi.hslf.usermodel.HSLFTextShape) shape).getText());
                            text.append("\n");
                        }
                    }
                }
                ppt.close();
                return text.toString();
            }
        } catch (Exception e) {
            return "[PPT解析失败: " + e.getMessage() + "]";
        }
    }

    /**
     * 解析OpenDocument Text文件 (.odt)
     * ODF文件是ZIP压缩包，包含content.xml
     */
    private String parseOdt(Path path) {
        return parseOdfContent(path, "odt");
    }

    /**
     * 解析OpenDocument Spreadsheet文件 (.ods)
     */
    private String parseOds(Path path) {
        return parseOdfContent(path, "ods");
    }

    /**
     * 解析OpenDocument Presentation文件 (.odp)
     */
    private String parseOdp(Path path) {
        return parseOdfContent(path, "odp");
    }

    /**
     * 解析ODF文件（从content.xml提取文本）
     */
    private String parseOdfContent(Path path, String type) {
        try (java.util.zip.ZipFile zipFile = new java.util.zip.ZipFile(path.toFile())) {
            java.util.zip.ZipEntry contentEntry = zipFile.getEntry("content.xml");
            if (contentEntry == null) {
                return "[" + type.toUpperCase() + "文件缺少content.xml]";
            }

            try (java.io.InputStream is = zipFile.getInputStream(contentEntry)) {
                String xmlContent = new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                // 简单提取文本内容（去除XML标签）
                String text = xmlContent.replaceAll("<[^>]+>", " ")
                        .replaceAll("\\s+", " ")
                        .trim();

                if (text.isEmpty()) {
                    return "[" + type.toUpperCase() + "文件无可提取文本]";
                }
                return text;
            }
        } catch (Exception e) {
            return "[" + type.toUpperCase() + "解析失败: " + e.getMessage() + "]";
        }
    }

    /**
     * 解析图片文件（使用VLM生成描述，替代传统OCR）
     */
    private String parseImage(Path path) {
        try {
            byte[] bytes = Files.readAllBytes(path);
            String ext = getExtension(path.getFileName().toString());
            String mime = switch (ext) {
                case "jpg", "jpeg" -> "image/jpeg";
                case "gif" -> "image/gif";
                case "webp" -> "image/webp";
                case "bmp" -> "image/bmp";
                default -> "image/png";
            };
            // 使用VLM生成图片描述（Caption-first策略）
            String caption = imageService.generateCaption(bytes, mime);
            return caption;
        } catch (IOException e) {
            return "[图片解析失败: " + e.getMessage() + "]";
        }
    }

    /**
     * AI智能分析文档分类（根据文档内容自动推荐分类和目标库）
     */
    public java.util.Map<String, String> analyzeAndClassify(String filename, String content) {
        try {
            // 截断内容避免过长
            String truncatedContent = content.length() > 2000 ? content.substring(0, 2000) + "..." : content;

            String systemPrompt = """
                    你是一个文档分类专家。请分析以下文档内容，推荐：
                    1. 一级分类（categoryL1）：政治、军事、经济、科技、社会、文化、其他
                    2. 资料类型（docType）：report（研究报告）、dynamic（动态信息）、translation（图书）、chart（图表数据）、policy（政策文件）、news（新闻资讯）
                    3. 目标库（library）：report、dynamic、translation、chart、policy、news
                    
                    请以JSON格式返回：{"categoryL1": "...", "docType": "...", "library": "...", "reason": "..."}
                    只返回JSON，不要其他文字。
                    """;

            String response = llmService.chatWithActive(systemPrompt,
                    "文件名: " + filename + "\n\n内容:\n" + truncatedContent);

            // 解析LLM返回的JSON
            String json = response.trim();
            if (json.startsWith("```")) {
                json = json.replaceAll("^```\\w*\\n?", "").replaceAll("\\n?```$", "");
            }

            JsonNode node = mapper.readTree(json);
            java.util.Map<String, String> result = new java.util.HashMap<>();
            result.put("categoryL1", node.has("categoryL1") ? node.get("categoryL1").asText() : "其他");
            result.put("docType", node.has("docType") ? node.get("docType").asText() : "report");
            result.put("library", node.has("library") ? node.get("library").asText() : "report");
            result.put("reason", node.has("reason") ? node.get("reason").asText() : "AI分析完成");
            return result;

        } catch (Exception e) {
            // 默认分类
            java.util.Map<String, String> defaultResult = new java.util.HashMap<>();
            defaultResult.put("categoryL1", "其他");
            defaultResult.put("docType", "report");
            defaultResult.put("library", "report");
            defaultResult.put("reason", "AI分析失败: " + e.getMessage());
            return defaultResult;
        }
    }

    /**
     * 查询项目已有词条，构建LLM参考上下文（智能标签功能）
     * 将已有词条的标题和关键词作为prompt的一部分，让LLM在提取新词条时优先复用已有标签体系
     * @param projectId 项目ID（数据隔离）
     * @param library 目标资料库（可为null，表示查询所有库）
     * @return 拼接好的上下文文本，若无已有词条则返回空字符串
     */
    private String buildExistingEntriesContext(Long projectId, String library) {
        LambdaQueryWrapper<KnowledgeEntry> wrapper = new LambdaQueryWrapper<>();
        if (projectId != null) {
            wrapper.eq(KnowledgeEntry::getProjectId, projectId);
        }
        // 排除图片和表格类条目，只参考概念性词条
        wrapper.ne(KnowledgeEntry::getEntryType, "image")
               .ne(KnowledgeEntry::getEntryType, "table");
        // 限制数量避免prompt过长
        wrapper.last("LIMIT 50");

        List<KnowledgeEntry> existingEntries = knowledgeEntryMapper.selectList(wrapper);
        if (existingEntries.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("\n\n【已有词条参考（请优先复用以下词条的关键词和标签，保持标签体系一致性）】：\n");
        for (KnowledgeEntry entry : existingEntries) {
            sb.append("- ").append(entry.getTitle());
            if (entry.getKeywords() != null && !entry.getKeywords().isEmpty()) {
                sb.append(" [关键词: ").append(entry.getKeywords()).append("]");
            }
            if (entry.getEntryLibrary() != null) {
                sb.append(" (库: ").append(entry.getEntryLibrary()).append(")");
            }
            sb.append("\n");
        }
        sb.append("\n请在提取新词条时，优先使用上述已有词条的关键词作为标签(tags)，避免创建重复或含义相近的新标签。\n");

        log.info("Smart tags: loaded {} existing entries as context for project {}", existingEntries.size(), projectId);
        return sb.toString();
    }

    /**
     * 从文档文本中提取智能标签（用于图表库）
     * 参考已有词条的关键词，生成与已有体系一致的标签列表
     * @param text 文档文本内容
     * @param projectId 项目ID
     * @return 逗号分隔的标签字符串，提取失败返回null
     */
    private String extractSmartTags(String text, Long projectId) {
        String existingContext = buildExistingEntriesContext(projectId, null);

        String systemPrompt = """
                你是一个标签提取专家。请从以下文本中提取关键标签。

                【要求】：
                1. 提取5-10个高价值的标签/关键词
                2. 优先使用已有词条中出现的关键词，保持标签体系一致性
                3. 标签应简洁，2-6个字
                4. 以逗号分隔返回

                【返回格式】：
                只返回逗号分隔的标签列表，不要其他文字。
                示例：社区医疗,慢病管理,分级诊疗,基层医保
                """ + existingContext;

        try {
            // 截断文本避免prompt过长
            String truncatedText = text.length() > 3000 ? text.substring(0, 3000) + "..." : text;
            String response = llmService.chatWithActive(systemPrompt,
                    "请从以下文本中提取标签：\n\n" + truncatedText);
            // 清理响应中可能的markdown标记
            String tags = response.trim();
            if (tags.startsWith("```")) {
                tags = tags.replaceAll("^```\\w*\\n?", "").replaceAll("\\n?```$", "").trim();
            }
            return tags;
        } catch (Exception e) {
            log.warn("Smart tag extraction failed for project {}: {}", projectId, e.getMessage());
            return null;
        }
    }

    /**
     * 将智能标签应用到指定文档关联的图表词条
     * @param docId 文档ID
     * @param smartTags 逗号分隔的智能标签
     * @param projectId 项目ID
     */
    private void updateChartTagsForDocument(Long docId, String smartTags, Long projectId) {
        try {
            LambdaQueryWrapper<KnowledgeEntry> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(KnowledgeEntry::getDocumentId, docId)
                   .eq(KnowledgeEntry::getProjectId, projectId)
                   .eq(KnowledgeEntry::getEntryLibrary, "chart");
            List<KnowledgeEntry> chartEntries = knowledgeEntryMapper.selectList(wrapper);

            for (KnowledgeEntry entry : chartEntries) {
                String existing = entry.getKeywords() != null ? entry.getKeywords() : "";
                // 合并标签，避免重复
                StringBuilder merged = new StringBuilder(existing);
                for (String tag : smartTags.split(",")) {
                    String t = tag.trim();
                    if (!t.isEmpty() && !existing.contains(t)) {
                        if (merged.length() > 0) merged.append(",");
                        merged.append(t);
                    }
                }
                if (!merged.toString().equals(existing)) {
                    entry.setKeywords(merged.toString());
                    knowledgeEntryMapper.updateById(entry);
                    vectorSearchService.indexEntry(entry);
                    log.info("Updated chart entry '{}' tags: {}", entry.getTitle(), merged);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to update chart tags for document {}: {}", docId, e.getMessage());
        }
    }

    /**
     * 解析LLM返回的词条JSON
     */
    private List<KnowledgeEntry> parseEntriesFromResponse(String response, Document doc, String library) {
        List<KnowledgeEntry> entries = new ArrayList<>();
        String now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

        try {
            // 清理响应中可能的markdown代码块标记
            String json = response.trim();
            if (json.startsWith("```")) {
                json = json.replaceAll("^```\\w*\\n?", "").replaceAll("\\n?```$", "");
            }

            JsonNode arr = mapper.readTree(json);
            if (arr.isArray()) {
                for (JsonNode node : arr) {
                    KnowledgeEntry entry = new KnowledgeEntry();
                    entry.setTitle(node.has("title") ? node.get("title").asText() : "未命名词条");
                    
                    // 词条类型支持 type 或 entry_type
                    String type = "concept";
                    if (node.has("type")) {
                        type = node.get("type").asText();
                    } else if (node.has("entry_type")) {
                        type = node.get("entry_type").asText();
                    }
                    List<String> allowedTypes = List.of("concept", "entity", "thesis", "methodology", "finding", "comparison", "synthesis", "source", "query");
                    if (!allowedTypes.contains(type.toLowerCase())) {
                        type = "concept";
                    }
                    entry.setEntryType(type.toLowerCase());
                    
                    entry.setEntryLibrary(library);
                    entry.setDocumentId(doc.getId());
                    entry.setSourceName(doc.getTitle());
                    entry.setContent(node.has("content") ? node.get("content").asText() : "");
                    entry.setDescription(node.has("description") ? node.get("description").asText() : "");
                    
                    // tags -> keywords 扁平化以逗号分隔存储
                    if (node.has("tags") && node.get("tags").isArray()) {
                        List<String> tagList = new ArrayList<>();
                        for (JsonNode t : node.get("tags")) {
                            tagList.add(t.asText());
                        }
                        entry.setKeywords(String.join(",", tagList));
                    } else if (node.has("keywords")) {
                        entry.setKeywords(node.get("keywords").asText());
                    } else {
                        entry.setKeywords("");
                    }
                    
                    // related 扁平化以逗号分隔存储
                    if (node.has("related") && node.get("related").isArray()) {
                        List<String> relList = new ArrayList<>();
                        for (JsonNode r : node.get("related")) {
                            String relText = r.asText().trim();
                            if (!relText.isEmpty() && !"None".equalsIgnoreCase(relText) && !"null".equalsIgnoreCase(relText)) {
                                relList.add(relText);
                            }
                        }
                        entry.setRelated(relList.isEmpty() ? null : String.join(",", relList));
                        // 同步反向关联：确保关联关系双向存储
                        if (entry.getRelated() != null && !entry.getRelated().isEmpty()) {
                            try {
                                syncReverseRelations(entry);
                            } catch (Exception e) {
                                log.warn("Failed to sync reverse relations for entry '{}': {}", 
                                        entry.getTitle(), e.getMessage());
                            }
                        }
                    } else if (node.has("related")) {
                        String relText = node.get("related").asText().trim();
                        if (relText.isEmpty() || "None".equalsIgnoreCase(relText) || "null".equalsIgnoreCase(relText)) {
                            entry.setRelated(null);
                        } else {
                            entry.setRelated(relText);
                        }
                    } else {
                        entry.setRelated(null);
                    }
                    
                    entry.setCategoryL1(doc.getCategoryL1());
                    entry.setCategoryL2(doc.getCategoryL2());
                    entry.setStatus(isAutoApprove() ? "approved" : "pending");
                    entry.setConfidence(0.85);
                    entry.setCreatedAt(now);
                    entry.setProjectId(doc.getProjectId());
                    entries.add(entry);
                }
            }
        } catch (Exception e) {
            // 解析失败时记录错误日志，不创建垃圾词条
            // IRON RULE: 上传的资料不是词条！LLM 抽取失败 = 不创建条目
            log.error("LLM extraction JSON parse failed for document '{}' (id={}): {} - raw response (first 300 chars): {}",
                    doc.getTitle(), doc.getId(), e.getMessage(),
                    response.substring(0, Math.min(300, response.length())));
            // 返回空列表，不创建任何词条
        }

        return entries;
    }

    private String mapDocTypeToLibrary(String docType) {
        if (docType == null) return "report";
        return switch (docType) {
            case "dynamic" -> "dynamic";
            case "translation" -> "translation";
            case "chart" -> "chart";
            default -> "report";
        };
    }

    /**
     * 为新建词条同步反向关联：遍历其关联的每个词条，将当前词条标题追加到目标词条的 related 字段中
     */
    private void syncReverseRelations(KnowledgeEntry entry) {
        if (entry.getRelated() == null || entry.getRelated().trim().isEmpty()) return;
        if (entry.getTitle() == null || entry.getTitle().isEmpty()) return;
        
        Long pid = entry.getProjectId();
        String[] relatedTitles = entry.getRelated().split("\\s*,\\s*");
        
        for (String relTitle : relatedTitles) {
            if (relTitle.trim().isEmpty()) continue;
            
            try {
                LambdaQueryWrapper<KnowledgeEntry> wrapper = new LambdaQueryWrapper<>();
                wrapper.eq(KnowledgeEntry::getTitle, relTitle.trim());
                if (pid != null) wrapper.eq(KnowledgeEntry::getProjectId, pid);
                // IRON RULE: 使用 selectList 而非 selectOne，因为有同名条目（多 chunk 抽取产生重复标题）
                java.util.List<KnowledgeEntry> targets = knowledgeEntryMapper.selectList(wrapper);
                
                if (targets != null) {
                    for (KnowledgeEntry target : targets) {
                        java.util.Set<String> targetRelated = new java.util.HashSet<>();
                        if (target.getRelated() != null && !target.getRelated().isEmpty()) {
                            targetRelated.addAll(java.util.Arrays.asList(target.getRelated().split("\\s*,\\s*")));
                        }
                        if (!targetRelated.contains(entry.getTitle().trim())) {
                            targetRelated.add(entry.getTitle().trim());
                            target.setRelated(String.join(",", targetRelated));
                            knowledgeEntryMapper.updateById(target);
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to sync reverse relation for '{}' -> '{}': {}", 
                        entry.getTitle(), relTitle, e.getMessage());
            }
        }
    }
}
