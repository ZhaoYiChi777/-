package com.intelligence.platform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("kg_build_events")
public class KGBuildEvent {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long jobId;
    private Long projectId;
    private String eventType;
    private String message;
    private String payloadJson;
    private String createdAt;
}
