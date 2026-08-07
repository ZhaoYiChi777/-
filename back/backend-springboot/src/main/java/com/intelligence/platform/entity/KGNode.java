package com.intelligence.platform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("kg_nodes")
public class KGNode {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String label;
    private String nodeType;
    private String description;
    private Integer communityId;
    private Long projectId;
}
