package com.intelligence.platform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("kg_relation_candidates")
public class KGRelationCandidate {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long projectId;
    private Long sourceEntryId;
    private Long targetEntryId;
    private String relationType;
    private Double confidence;
    private String evidence;
    private String reason;
    private String extractor;
    private Long graphVersion;
    private String status;
    private String createdAt;
}
