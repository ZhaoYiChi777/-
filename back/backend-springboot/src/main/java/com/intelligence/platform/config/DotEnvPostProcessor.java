package com.intelligence.platform.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.io.File;
import java.io.FileInputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/**
 * 从项目根目录的 .env 文件加载环境变量
 * 优先级低于系统环境变量，不会覆盖已有的 OS 环境变量
 *
 * 搜索顺序（从高到低）：
 *   1. 当前工作目录下的 .env
 *   2. backend-springboot 目录下的 .env（向上查找）
 *   3. 系统环境变量（不会被覆盖）
 */
public class DotEnvPostProcessor implements EnvironmentPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(DotEnvPostProcessor.class);
    private static final String PROPERTY_SOURCE_NAME = "dotenvProperties";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        // 搜索 .env 文件
        File envFile = findEnvFile();
        if (envFile == null || !envFile.exists()) {
            // 没有找到 .env 文件，不报错
            return;
        }

        try {
            Properties props = new Properties();
            try (FileInputStream fis = new FileInputStream(envFile)) {
                // 逐行解析，跳过注释行
                byte[] content = fis.readAllBytes();
                String text = new String(content);
                for (String line : text.split("\n")) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    int eqIdx = line.indexOf('=');
                    if (eqIdx < 0) continue;
                    String key = line.substring(0, eqIdx).trim();
                    String value = line.substring(eqIdx + 1).trim();
                    // 去除引号
                    if ((value.startsWith("\"") && value.endsWith("\""))
                            || (value.startsWith("'") && value.endsWith("'"))) {
                        value = value.substring(1, value.length() - 1);
                    }
                    // 去除行内注释（# 后的内容）
                    int commentIdx = value.indexOf(" #");
                    if (commentIdx > 0) {
                        value = value.substring(0, commentIdx).trim();
                    }
                    if (!key.isEmpty()) {
                        props.put(key, value);
                    }
                }
            }

            Map<String, Object> envMap = new HashMap<>();
            for (String key : props.stringPropertyNames()) {
                // 仅当系统环境变量中不存在该 key 时才加载（避免覆盖系统变量）
                if (!environment.getSystemEnvironment().containsKey(key)) {
                    envMap.put(key, props.getProperty(key));
                }
            }

            if (!envMap.isEmpty()) {
                environment.getPropertySources().addLast(
                        new MapPropertySource(PROPERTY_SOURCE_NAME, envMap));
                log.info("[DotEnv] Loaded {} variables from: {}", envMap.size(), envFile.getAbsolutePath());
            }
        } catch (Exception e) {
            log.warn("[DotEnv] Failed to load .env file: {}", e.getMessage());
        }
    }

    private File findEnvFile() {
        // 1. 当前工作目录
        File f = new File(".env");
        if (f.exists()) return f;

        // 2. 向上一级（backend-springboot 父目录）
        f = new File("../.env");
        if (f.exists()) return f;

        // 3. 用户 home 目录
        f = new File(System.getProperty("user.home"), ".env");
        if (f.exists()) return f;

        return null;
    }
}
