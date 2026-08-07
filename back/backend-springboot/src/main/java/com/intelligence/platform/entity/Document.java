package com.intelligence.platform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("documents")
public class Document {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String title;
    private String categoryL1;
    private String categoryL2;
    private String docType;
    private String filePath;
    private String fileHash;
    private String keywords;
    private String status;
    private String uploadTime;
    private String metaInfo;
    /** 来源信息（论文名、blog URL等，用户可手动补充） */
    private String sourceOrigin;
    /** 来源文件在 raw/sources/ 下的相对路径（参考 llm_wiki source-identity） */
    private String sourcePath;
    /** 来源路径的唯一标识（由 sourceIdentityForPath 生成） */
    private String sourceIdentity;
    /** 目录层级上下文（如 "论文 > 2024 > transformer"） */
    private String folderContext;
    /** 所属项目ID（数据隔离） */
    private Long projectId;
    /** URL（用于Blog/网页类文档） */
    private String url;
    /** 图表来源：引用的文档ID（用于图表关联来源文档） */
    private Long sourceDocId;
    /** 图表来源：引用的页码 */
    private Integer sourcePage;
}
