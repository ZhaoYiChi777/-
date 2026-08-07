package com.intelligence.platform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("decisions")
public class Decision {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String title;
    private String decisionType;
    private Double score;
    private String analysis;
    private String suggestion;
    private String content;
    private String priority;
    private String source;
    private String category;
    private String createdAt;
    private Long projectId;
}
