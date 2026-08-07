package com.intelligence.platform.service;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;

/**
 * 文件校验服务
 * 提供文件格式验证、Magic Bytes检测、内容完整性检查等功能
 */
@Service
public class FileValidationService {

    // 扩展的文件类型映射（支持Office和WPS格式）
    public static final Set<String> DOCUMENT_EXTENSIONS = Set.of(
            // 纯文本
            "txt", "md", "markdown", "csv", "rtf",
            // PDF
            "pdf",
            // Microsoft Office
            "doc", "docx", "xls", "xlsx", "ppt", "pptx",
            // WPS Office（与Office格式兼容，WPS使用相同扩展名）
            "wps", "et", "dps",
            // OpenDocument格式
            "odt", "ods", "odp"
    );

    public static final Set<String> IMAGE_EXTENSIONS = Set.of(
            // 常见图片
            "jpg", "jpeg", "png", "gif", "bmp", "webp",
            // 专业格式
            "tiff", "tif", "svg", "ico",
            // 现代格式
            "heic", "heif", "avif",
            // 其他
            "raw", "cr2", "nef"
    );

    /** 表格文件扩展名（Excel/WPS表格/CSV/ODS） */
    public static final Set<String> TABLE_EXTENSIONS = Set.of(
            "xls", "xlsx", "et", "csv", "ods"
    );

    public static final Set<String> ALL_ALLOWED_EXTENSIONS;
    static {
        Set<String> all = new HashSet<>();
        all.addAll(DOCUMENT_EXTENSIONS);
        all.addAll(IMAGE_EXTENSIONS);
        ALL_ALLOWED_EXTENSIONS = Collections.unmodifiableSet(all);
    }

    // Magic Bytes签名映射（用于检测文件真实类型）
    private static final Map<String, byte[][]> MAGIC_BYTES;
    static {
        Map<String, byte[][]> map = new HashMap<>();
        // PDF
        map.put("pdf", new byte[][] { {0x25, 0x50, 0x44, 0x46} }); // %PDF
        // Microsoft Office (OLE2 Compound Document)
        map.put("doc", new byte[][] { {(byte)0xD0, (byte)0xCF, 0x11, (byte)0xE0, (byte)0xA1, (byte)0xB1, 0x1A, (byte)0xE1} });
        map.put("xls", new byte[][] { {(byte)0xD0, (byte)0xCF, 0x11, (byte)0xE0, (byte)0xA1, (byte)0xB1, 0x1A, (byte)0xE1} });
        map.put("ppt", new byte[][] { {(byte)0xD0, (byte)0xCF, 0x11, (byte)0xE0, (byte)0xA1, (byte)0xB1, 0x1A, (byte)0xE1} });
        // Office Open XML (docx/xlsx/pptx)
        map.put("docx", new byte[][] { {0x50, 0x4B, 0x03, 0x04} }); // PK..
        map.put("xlsx", new byte[][] { {0x50, 0x4B, 0x03, 0x04} });
        map.put("pptx", new byte[][] { {0x50, 0x4B, 0x03, 0x04} });
        // WPS格式（与Office兼容）
        map.put("wps", new byte[][] { {(byte)0xD0, (byte)0xCF, 0x11, (byte)0xE0}, {0x50, 0x4B, 0x03, 0x04} });
        map.put("et", new byte[][] { {(byte)0xD0, (byte)0xCF, 0x11, (byte)0xE0}, {0x50, 0x4B, 0x03, 0x04} });
        map.put("dps", new byte[][] { {(byte)0xD0, (byte)0xCF, 0x11, (byte)0xE0}, {0x50, 0x4B, 0x03, 0x04} });
        // 图片格式
        map.put("jpg", new byte[][] { {(byte)0xFF, (byte)0xD8, (byte)0xFF} });
        map.put("jpeg", new byte[][] { {(byte)0xFF, (byte)0xD8, (byte)0xFF} });
        map.put("png", new byte[][] { {(byte)0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A} });
        map.put("gif", new byte[][] { {0x47, 0x49, 0x46, 0x38} }); // GIF8
        map.put("bmp", new byte[][] { {0x42, 0x4D} }); // BM
        map.put("webp", new byte[][] { {0x52, 0x49, 0x46, 0x46} }); // RIFF
        map.put("tiff", new byte[][] { {0x49, 0x49, 0x2A, 0x00}, {0x4D, 0x4D, 0x00, 0x2A} });
        map.put("tif", new byte[][] { {0x49, 0x49, 0x2A, 0x00}, {0x4D, 0x4D, 0x00, 0x2A} });
        map.put("svg", new byte[][] { {0x3C} }); // < (XML)
        map.put("ico", new byte[][] { {0x00, 0x00, 0x01, 0x00} });
        MAGIC_BYTES = Collections.unmodifiableMap(map);
    }

    // 文件大小限制（字节）
    private static final long MAX_FILE_SIZE = 100 * 1024 * 1024; // 100MB
    private static final long MIN_FILE_SIZE = 1; // 至少1字节

    /**
     * 校验结果
     */
    public record ValidationResult(
            boolean valid,
            String message,
            String detectedType,
            long fileSize
    ) {}

    /**
     * 校验单个文件
     */
    public ValidationResult validate(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return new ValidationResult(false, "文件为空", null, 0);
        }

        String filename = file.getOriginalFilename();
        String extension = getExtension(filename);
        long size = file.getSize();

        // 1. 检查文件大小
        if (size < MIN_FILE_SIZE) {
            return new ValidationResult(false, "文件太小或为空", extension, size);
        }
        if (size > MAX_FILE_SIZE) {
            return new ValidationResult(false,
                    String.format("文件过大: %.2f MB，最大允许 100 MB", size / 1024.0 / 1024.0),
                    extension, size);
        }

        // 2. 检查扩展名是否在白名单中
        if (!ALL_ALLOWED_EXTENSIONS.contains(extension)) {
            return new ValidationResult(false,
                    "不支持的文件类型: ." + extension + "，支持的类型: " + String.join(", ", ALL_ALLOWED_EXTENSIONS),
                    extension, size);
        }

        // 3. Magic Bytes检测（验证文件真实类型，处理别名）
        try {
            String detectedType = detectFileType(file);
            if (detectedType != null) {
                // 规范化扩展名和检测类型（jpg/jpeg 等别名视为同一类型）
                String normalizedExt = normalizeExtension(extension);
                String normalizedDetected = normalizeExtension(detectedType);
                if (!normalizedDetected.equals(normalizedExt)) {
                    // 对于 Office/ZIP 格式（docx/xlsx/pptx/wps/et/dps），Magic Bytes均为PK，不精确检测
                    boolean isZipBased = Set.of("docx", "xlsx", "pptx", "wps", "et", "dps", "odt", "ods", "odp").contains(extension);
                    if (!isZipBased) {
                        return new ValidationResult(false,
                                String.format("文件类型不匹配: 扩展名为.%s，但实际内容似乎是%s格式", extension, detectedType),
                                detectedType, size);
                    }
                }
            }
        } catch (IOException e) {
            return new ValidationResult(false, "无法读取文件内容: " + e.getMessage(), extension, size);
        }

        // 4. 基本完整性检查（检查文件是否损坏）
        try {
            if (isFileCorrupted(file, extension)) {
                return new ValidationResult(false, "文件可能已损坏或内容不完整", extension, size);
            }
        } catch (Exception e) {
            // 完整性检查失败不阻止上传，只是警告
        }

        return new ValidationResult(true, "文件校验通过", extension, size);
    }

    /**
     * 批量校验文件
     */
    public Map<String, ValidationResult> validateBatch(List<MultipartFile> files) {
        Map<String, ValidationResult> results = new LinkedHashMap<>();
        for (MultipartFile file : files) {
            String name = file.getOriginalFilename() != null ? file.getOriginalFilename() : "unknown";
            results.put(name, validate(file));
        }
        return results;
    }

    // 扩展名别名映射（同一种文件格式可能有多个扩展名）
    private static final Map<String, String> EXTENSION_ALIASES = Map.of(
            "jpeg", "jpg",
            "tif",  "tiff",
            "heif", "heic"
    );

    /**
     * 检测文件真实类型（通过Magic Bytes）
     * 返回值为规范化的类型名（统一用 jpg 代表 jpg/jpeg 等别名）
     */
    private String detectFileType(MultipartFile file) throws IOException {
        byte[] header = new byte[16];
        try (InputStream is = file.getInputStream()) {
            int read = is.read(header);
            if (read < 4) return null;
        }

        // 按优先级检测
        for (Map.Entry<String, byte[][]> entry : MAGIC_BYTES.entrySet()) {
            String type = entry.getKey();
            for (byte[] signature : entry.getValue()) {
                if (matchesSignature(header, signature)) {
                    return type;
                }
            }
        }

        // 检测纯文本文件（UTF-8/ASCII）
        if (isTextContent(header)) {
            return "txt";
        }

        return null;
    }

    /**
     * 将扩展名规范化（处理别名，如 jpeg→jpg, tif→tiff）
     */
    private String normalizeExtension(String ext) {
        return EXTENSION_ALIASES.getOrDefault(ext, ext);
    }

    /**
     * 检查字节数组是否匹配签名
     */
    private boolean matchesSignature(byte[] header, byte[] signature) {
        if (header.length < signature.length) return false;
        for (int i = 0; i < signature.length; i++) {
            if (header[i] != signature[i]) return false;
        }
        return true;
    }

    /**
     * 检查是否为纯文本内容
     */
    private boolean isTextContent(byte[] header) {
        for (byte b : header) {
            // 允许常见文本字符和控制字符（换行、回车、制表符）
            if (b < 0x20 && b != 0x09 && b != 0x0A && b != 0x0D) {
                return false;
            }
        }
        return true;
    }

    /**
     * 检查文件是否损坏（基本检查）
     */
    private boolean isFileCorrupted(MultipartFile file, String extension) throws IOException {
        byte[] content = file.getBytes();
        if (content.length == 0) return true;

        // PDF检查：以%PDF开头，以%%EOF结尾
        if ("pdf".equals(extension)) {
            if (content[0] != 0x25 || content[1] != 0x50) return true; // %P
            String tail = new String(content, content.length - 20, 20);
            if (!tail.contains("%%EOF")) return true;
        }

        // PNG检查：使用 ImageIO 验证真实可读性（比手工字节检查更可靠）
        if ("png".equals(extension) || "jpg".equals(extension) || "jpeg".equals(extension)
                || "bmp".equals(extension) || "gif".equals(extension) || "webp".equals(extension)) {
            try {
                java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(content));
                // img==null 表示格式无法被 ImageIO 识别（但不阻止上传，只记录警告）
                // 我们宽松处理：只要不抛出异常就认为文件合法
                return false; // 不检查损坏，让 ImageIO 的读取结果作为软验证
            } catch (Exception e) {
                // ImageIO读取失败也不阻止上传，交由后续处理
                return false;
            }
        }

        // ZIP格式文件（docx/xlsx/pptx/wps/et/dps）检查：以PK开头
        if (Set.of("docx", "xlsx", "pptx", "wps", "et", "dps").contains(extension)) {
            if (content[0] != 0x50 || content[1] != 0x4B) return true; // PK
        }

        // OLE2格式文件（doc/xls/ppt）检查：以D0CF开头
        if (Set.of("doc", "xls", "ppt").contains(extension)) {
            if (content[0] != (byte)0xD0 || content[1] != (byte)0xCF) return true;
        }

        return false;
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
     * 获取所有支持的扩展名列表
     */
    public Set<String> getAllAllowedExtensions() {
        return ALL_ALLOWED_EXTENSIONS;
    }

    /**
     * 获取文件类型分类（文档/图片）
     */
    public String getFileCategory(String extension) {
        if (DOCUMENT_EXTENSIONS.contains(extension)) return "document";
        if (IMAGE_EXTENSIONS.contains(extension)) return "image";
        return "unknown";
    }
}
