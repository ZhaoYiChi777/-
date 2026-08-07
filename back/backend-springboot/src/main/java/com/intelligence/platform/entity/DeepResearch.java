package com.intelligence.platform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 深度研究任务实体
 * 参考 llm_wiki 的 deep-research 功能
 */
@Data
@TableName("deep_researches")
public class DeepResearch {
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 研究主题 */
    private String topic;
    /** 研究状态：queued/running/completed/failed */
    private String status;
    /** 搜索查询列表（JSON数组） */
    private String searchQueries;
    /** 搜索到的来源数量 */
    private Integer sourceCount;
    /** 综合分析结果 */
    private String synthesis;
    /** 深度思考过程 */
    private String thinkingProcess;
    /** 进度百分比 */
    private Integer progress;
    /** 错误信息 */
    private String error;
    /** 使用的LLM配置名称 */
    private String llmConfig;
    /** 创建时间 */
    private String createdAt;
    /** 完成时间 */
    private String completedAt;
    /** 所属项目ID */
    private Long projectId;
}
