package com.intelligence.platform.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.intelligence.platform.entity.LlmConfig;
import com.intelligence.platform.mapper.LlmConfigMapper;
import com.intelligence.platform.service.LlmService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * LLM配置管理（后台"系统配置"页面使用）
 * 参考 llm_wiki 的 LLM Provider 配置模型
 */
@RestController
@RequestMapping("/api/llm-configs")
@CrossOrigin(origins = "*")
public class LlmConfigController {

    @Autowired
    private LlmConfigMapper llmConfigMapper;
    @Autowired
    private LlmService llmService;

    /**
     * 获取所有LLM配置
     */
    @GetMapping
    public List<LlmConfig> list() {
        return llmConfigMapper.selectList(
                new LambdaQueryWrapper<LlmConfig>().orderByAsc(LlmConfig::getId));
    }

    /**
     * 获取当前启用的LLM配置
     */
    @GetMapping("/active")
    public LlmConfig getActive() {
        List<LlmConfig> configs = llmConfigMapper.selectList(
                new LambdaQueryWrapper<LlmConfig>()
                        .eq(LlmConfig::getEnabled, true)
                        .eq(LlmConfig::getPurpose, "chat"));
        return configs.isEmpty() ? null : configs.get(0);
    }

    /**
     * 获取当前启用的Embedding配置
     */
    @GetMapping("/active-embedding")
    public LlmConfig getActiveEmbedding() {
        List<LlmConfig> configs = llmConfigMapper.selectList(
                new LambdaQueryWrapper<LlmConfig>()
                        .eq(LlmConfig::getEnabled, true)
                        .eq(LlmConfig::getPurpose, "embedding"));
        return configs.isEmpty() ? null : configs.get(0);
    }

    @GetMapping("/{id}")
    public LlmConfig get(@PathVariable Long id) {
        return llmConfigMapper.selectById(id);
    }

    /**
     * 创建LLM配置
     */
    @PostMapping("/")
    public Map<String, Object> create(@RequestBody LlmConfig config) {
        llmConfigMapper.insert(config);
        return Map.of("id", config.getId(), "message", "创建成功");
    }

    /**
     * 更新LLM配置
     */
    @PutMapping("/{id}")
    public Map<String, Object> update(@PathVariable Long id, @RequestBody LlmConfig config) {
        config.setId(id);
        llmConfigMapper.updateById(config);
        return Map.of("message", "更新成功");
    }

    /**
     * 启用/禁用配置
     */
    @PutMapping("/{id}/toggle")
    public Map<String, Object> toggle(@PathVariable Long id) {
        LlmConfig config = llmConfigMapper.selectById(id);
        if (config == null) throw new RuntimeException("配置不存在");
        config.setEnabled(!config.getEnabled());
        llmConfigMapper.updateById(config);
        return Map.of("message", config.getEnabled() ? "已启用" : "已禁用");
    }

    /**
     * 删除LLM配置
     */
    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable Long id) {
        llmConfigMapper.deleteById(id);
        return Map.of("message", "删除成功");
    }

    /**
     * 获取所有支持的LLM提供商列表
     */
    @GetMapping("/providers")
    public List<Map<String, String>> getProviders() {
        return llmService.getSupportedProviders();
    }

    /**
     * 测试LLM连接（实际发送请求验证）
     */
    @PostMapping("/test")
    public ResponseEntity<?> testConnection(@RequestBody LlmConfig config) {
        try {
            String reply = llmService.chat(config, "你是一个测试助手。", "请回复'连接成功'四个字。");
            if (reply == null || reply.isEmpty()) {
                return ResponseEntity.ok(Map.of("success", false, "message", "连接测试失败：收到空响应"));
            }
            // 截取前100字符
            String preview = reply.length() > 100 ? reply.substring(0, 100) + "..." : reply;
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "连接测试成功（" + config.getProvider() + " / " + config.getModel() + "）",
                    "reply", preview
            ));
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of(
                    "success", false,
                    "message", "连接失败: " + e.getMessage()
            ));
        }
    }

    private String resolveApiUrl(LlmConfig config) {
        return switch (config.getProvider()) {
            case "openai" -> "https://api.openai.com/v1/chat/completions";
            case "anthropic" -> "https://api.anthropic.com/v1/messages";
            case "google" -> "https://generativelanguage.googleapis.com/v1beta/models/" + config.getModel() + ":streamGenerateContent";
            case "ollama" -> (config.getBaseUrl() != null ? config.getBaseUrl() : "http://localhost:11434") + "/v1/chat/completions";
            case "azure" -> config.getBaseUrl();
            // 中国提供商
            case "deepseek" -> "https://api.deepseek.com/chat/completions";
            case "qwen" -> "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions";
            case "minimax" -> "https://api.minimax.chat/v1/text/chatcompletion_v2";
            case "moonshot" -> "https://api.moonshot.cn/v1/chat/completions";
            case "stepfun" -> "https://api.stepfun.com/v1/chat/completions";
            case "xiaomi" -> "https://api.xiaomi.com/v1/chat/completions";
            case "hunyuan" -> "https://api.hunyuan.cloud.tencent.com/v1/chat/completions";
            case "doubao" -> "https://ark.cn-beijing.volces.com/api/v3/chat/completions";
            case "siliconflow" -> (config.getBaseUrl() != null ? config.getBaseUrl() : "https://api.siliconflow.cn/v1") + "/chat/completions";
            default -> config.getBaseUrl() != null ? config.getBaseUrl() : "unknown";
        };
    }
}
