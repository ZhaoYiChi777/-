package com.intelligence.platform.service;

import com.intelligence.platform.entity.Setting;
import com.intelligence.platform.mapper.SettingMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Path;

/**
 * 来源身份服务（参考 llm_wiki 的 source-identity.ts）
 * 从文件路径生成唯一标识和目录上下文
 */
@Service
public class SourceIdentityService {

    private static final String RAW_SOURCES_PREFIX = "raw/sources/";
    private static final String RAW_SOURCES_MARKER = "/raw/sources/";

    @Value("${upload.dir:./uploads}")
    private String uploadDir;

    @Autowired(required = false)
    private SettingMapper settingMapper;

    /**
     * 获取实际使用的上传目录（优先从数据库读取，回退到配置文件）
     */
    public String getEffectiveUploadDir() {
        if (settingMapper != null) {
            try {
                Setting setting = settingMapper.selectById("upload_dir");
                if (setting != null && setting.getValue() != null && !setting.getValue().isBlank()) {
                    return setting.getValue();
                }
            } catch (Exception ignored) {
                // 数据库不可用时回退到配置文件
            }
        }
        return uploadDir;
    }

    /**
     * 获取 raw/sources 目录的绝对路径
     */
    public Path getSourcesRoot() {
        return Path.of(getEffectiveUploadDir(), "raw", "sources");
    }

    /**
     * 从文件路径生成来源唯一标识（参考 sourceIdentityForPath）
     * 去除 raw/sources/ 前缀，保留相对路径
     */
    public String sourceIdentityForPath(String filePath) {
        if (filePath == null) return "";
        String normalized = filePath.replace('\\', '/');

        // 去除 uploadDir 前缀
        String uploadPrefix = getEffectiveUploadDir().replace('\\', '/');
        if (normalized.startsWith(uploadPrefix)) {
            normalized = normalized.substring(uploadPrefix.length());
        }
        if (normalized.startsWith("/")) normalized = normalized.substring(1);

        // 去除 raw/sources/ 前缀
        if (normalized.toLowerCase().startsWith(RAW_SOURCES_PREFIX.toLowerCase())) {
            return normalized.substring(RAW_SOURCES_PREFIX.length());
        }

        // 查找 /raw/sources/ 标记
        int markerIdx = normalized.toLowerCase().indexOf(RAW_SOURCES_MARKER.toLowerCase());
        if (markerIdx >= 0) {
            return normalized.substring(markerIdx + RAW_SOURCES_MARKER.length());
        }

        // 回退：返回文件名
        int lastSlash = normalized.lastIndexOf('/');
        return lastSlash >= 0 ? normalized.substring(lastSlash + 1) : normalized;
    }

    /**
     * 从来源路径生成目录上下文（参考 folderContextForSourcePath）
     * 例如 "raw/sources/论文/2024/transformer.pdf" → "论文 > 2024"
     */
    public String folderContextForPath(String filePath) {
        if (filePath == null) return "";
        String identity = sourceIdentityForPath(filePath);

        // 去掉文件名，只保留目录部分
        int lastSlash = identity.lastIndexOf('/');
        if (lastSlash <= 0) return "";

        String dirPart = identity.substring(0, lastSlash);
        // 将 / 替换为 " > "
        return dirPart.replace('/', ' ').replace('\\', ' ')
                .replaceAll("\\s+", " > ")
                .trim();
    }

    /**
     * 计算来源文件应存放的路径（在 raw/sources/ 下按分类存放）
     * @param fileName 原始文件名
     * @param categoryL1 一级分类（如 "论文"、"报告"）
     * @param categoryL2 二级分类（如 "2024"）
     * @return 相对于 raw/sources/ 的存储路径
     */
    public String computeSourcePath(String fileName, String categoryL1, String categoryL2) {
        StringBuilder sb = new StringBuilder();
        if (categoryL1 != null && !categoryL1.isEmpty()) {
            sb.append(categoryL1).append("/");
        }
        if (categoryL2 != null && !categoryL2.isEmpty()) {
            sb.append(categoryL2).append("/");
        }
        sb.append(fileName);
        return sb.toString();
    }
}
