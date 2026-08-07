package com.intelligence.platform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("risk_alerts")
public class RiskAlert {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String title;
    private String severity;
    private String description;
    private String sourceA;
    private String sourceB;
    private String category;
    private String detectedAt;
    private String reporter;
    private Long projectId;
}
