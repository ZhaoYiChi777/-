package com.intelligence.platform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("kg_build_jobs")
public class KGBuildJob {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long projectId;
    private String status;
    private String buildMode;
    private Long graphVersion;
    private Integer totalEntries;
    private Integer processedEntries;
    private Integer nodeCount;
    private Integer edgeCount;
    private String errorMessage;
    private String startedAt;
    private String finishedAt;
}
