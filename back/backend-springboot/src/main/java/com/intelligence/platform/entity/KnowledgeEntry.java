package com.intelligence.platform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * LLM抽取的知识词条实体
 * 替代原始"已上传资料"，存储LLM从文档中抽取的结构化词条
 */
@Data
@TableName("knowledge_entries")
public class KnowledgeEntry {
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 词条标题 */
    private String title;
    /** 词条类型：concept/entity/thesis/methodology/finding/comparison/synthesis/source/query */
    private String entryType;
    /** 所属资料库：report/dynamic/translation/chart */
    private String entryLibrary;
    /** 来源文档ID */
    private Long documentId;
    /** 来源文档名称 */
    private String sourceName;
    /** 词条正文内容 (支持 Markdown) */
    private String content;
    /** 标签/关键词，逗号分隔 */
    private String keywords;
    /** 分类L1 */
    private String categoryL1;
    /** 分类L2 */
    private String categoryL2;
    /** 审核状态：pending(待审核)/approved(已审核)/rejected(已拒绝) */
    private String status;
    /** LLM抽取置信度 */
    private Double confidence;
    /** 创建时间 */
    private String createdAt;
    /** 审核人 */
    private String reviewer;
    /** 媒体类型：text(纯文本)/table(表格)/image(图片) */
    private String mediaType;
    /** 媒体文件路径（图片/文件的磁盘路径） */
    private String mediaPath;
    /** 来源信息（论文名、blog URL、PPT页码等） */
    private String sourceOrigin;
    /** 表格的Markdown格式存储 */
    private String tableMarkdown;
    /** 所属项目ID（数据隔离） */
    private Long projectId;
    /** 词条描述/释义 */
    private String description;
    /** 关联词条（以逗号分隔） */
    private String related;
}
