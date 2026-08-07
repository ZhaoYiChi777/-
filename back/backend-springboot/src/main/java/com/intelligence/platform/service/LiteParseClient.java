package com.intelligence.platform.service;

import com.intelligence.platform.entity.Setting;
import com.intelligence.platform.mapper.SettingMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * LiteParse 本地 CLI 客户端
 * 调用 lit parse 命令将文档解析为 Markdown
 * 支持：PDF、DOCX、PPTX、Excel、图片等格式
 */
@Service
public class LiteParseClient {

    private static final Logger log = LoggerFactory.getLogger(LiteParseClient.class);
    private static final int TIMEOUT_MINUTES = 5;

    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of(
            "pdf", "docx", "doc", "pptx", "ppt", "xlsx", "xls",
            "jpg", "jpeg", "png", "gif", "bmp", "webp", "tiff", "tif"
    );

    @Autowired
    private SettingMapper settingMapper;

    /**
     * 获取 LiteParse CLI 路径（从 settings 表）
     */
    private String getCliPath() {
        Setting setting = settingMapper.selectById("liteparse_cli_path");
        if (setting == null || setting.getValue() == null || setting.getValue().isEmpty()) {
            return null;
        }
        return setting.getValue();
    }

    /**
     * 检查 LiteParse 是否可用（settings 中已启用且 CLI 路径可执行）
     */
    public boolean isAvailable() {
        Setting enabled = settingMapper.selectById("liteparse_enabled");
        if (enabled == null || !"true".equals(enabled.getValue())) {
            return false;
        }

        String cliPath = getCliPath();
        if (cliPath == null) {
            return false;
        }

        // 如果是绝对路径，直接检查
        Path path = Path.of(cliPath);
        if (path.isAbsolute()) {
            return Files.exists(path) && Files.isExecutable(path);
        }

        // 如果只是命令名（如 "lit"），通过 which 检查 PATH 中是否存在
        try {
            Process p = new ProcessBuilder("which", cliPath).start();
            boolean finished = p.waitFor(5, TimeUnit.SECONDS);
            return finished && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 检查文件扩展名是否被 LiteParse 支持
     */
    public boolean isSupported(String extension) {
        return extension != null && SUPPORTED_EXTENSIONS.contains(extension.toLowerCase());
    }

    /**
     * 调用 LiteParse CLI 解析文件为 Markdown
     * @param file 要解析的文件路径
     * @return 解析后的 Markdown 文本
     * @throws Exception 解析失败时抛出异常
     */
    public String parse(Path file) throws Exception {
        String cliPath = getCliPath();
        if (cliPath == null) {
            throw new RuntimeException("LiteParse CLI path not configured. Set 'liteparse_cli_path' in settings.");
        }

        if (!Files.exists(file)) {
            throw new RuntimeException("File not found: " + file);
        }

        log.info("[LiteParse] Parsing file: {}", file);

        ProcessBuilder pb = new ProcessBuilder(
                cliPath, "parse",
                file.toString(),
                "--format", "markdown",
                "--image-mode", "off",
                "-q"
        );
        pb.redirectErrorStream(false);

        Process process = pb.start();

        StringBuilder stdout = new StringBuilder();
        StringBuilder stderr = new StringBuilder();

        // 读取 stdout
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                stdout.append(line).append("\n");
            }
        }

        // 读取 stderr
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                stderr.append(line).append("\n");
            }
        }

        boolean finished = process.waitFor(TIMEOUT_MINUTES, TimeUnit.MINUTES);
        if (!finished) {
            process.destroyForcibly();
            throw new RuntimeException("LiteParse parsing timed out after " + TIMEOUT_MINUTES + " minutes");
        }

        int exitCode = process.exitValue();
        if (exitCode != 0) {
            String errMsg = stderr.toString().trim();
            log.error("[LiteParse] Process exited with code {}: {}", exitCode, errMsg);
            throw new RuntimeException("LiteParse parsing failed (exit code " + exitCode + "): " + errMsg);
        }

        String result = stdout.toString().trim();
        if (result.isEmpty()) {
            log.warn("[LiteParse] Empty output for file: {}", file);
            return "";
        }

        log.info("[LiteParse] Parsed {} successfully: {} chars", file.getFileName(), result.length());
        return result;
    }
}
