package com.intelligence.platform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("kg_edges")
public class KGEdge {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long sourceId;
    private Long targetId;
    private String edgeType;
    private Double weight;
    private Long projectId;
}
