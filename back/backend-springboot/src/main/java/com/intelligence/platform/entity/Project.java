package com.intelligence.platform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 项目实体（参考 llm_wiki WikiProject）
 * 每个项目拥有独立的数据空间，数据不互通
 */
@Data
@TableName("projects")
public class Project {
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 项目名称 */
    private String name;
    /** 项目描述 */
    private String description;
    /** 项目状态：active/archived */
    private String status;
    /** 创建时间 */
    private String createdAt;
}
