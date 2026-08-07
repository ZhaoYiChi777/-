package com.intelligence.platform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("qa_records")
public class QARecord {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String question;
    private String answer;
    private Double confidence;
    private String sources;
    private String userName;
    private String sessionId;
    private String createdAt;
    private String category;
    private Long projectId;
    private String images;
    private String tables;
}
