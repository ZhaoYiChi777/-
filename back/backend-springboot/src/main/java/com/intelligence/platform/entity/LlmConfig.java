package com.intelligence.platform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * LLM配置实体
 * 参考 llm_wiki 的 LLM Provider 配置模型
 *
 * purpose 说明：
 *   chat      - 主对话 LLM（问答）
 *   extract   - 词条抽取专用 LLM
 *   vlm       - 视觉语言模型（图片处理 / OCR）
 *   embedding - 文本向量化（转向量数据库）
 *   rerank    - 检索重排序
 *   ocr       - 同 vlm（兼容旧版本）
 *   both      - 同时支持 chat + embedding
 */
@Data
@TableName("llm_configs")
public class LlmConfig {
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 配置名称 */
    private String name;
    /** 提供商：siliconflow/deepseek/qwen/moonshot/minimax/stepfun/hunyuan/doubao/openai/anthropic/google/ollama/azure/custom */
    private String provider;
    /** API Key */
    private String apiKey;
    /** 模型名称 */
    private String model;
    /** Base URL (用于 siliconflow/ollama/custom 等) */
    private String baseUrl;
    /** 是否启用 */
    private Boolean enabled;
    /** 用途：chat/extract/vlm/embedding/rerank/ocr/both */
    private String purpose;
    /** 上下文窗口大小 */
    private Integer maxContextSize;
    /** 自定义API模式：chat_completions/anthropic_messages/embeddings/rerank */
    private String apiMode;
    /** Azure API版本 */
    private String azureApiVersion;
    /** 创建时间 */
    private String createdAt;
    /** 备注 */
    private String description;
}
