package com.intelligence.platform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("analysis_reports")
public class AnalysisReport {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String title;
    private String analysisType;
    private String categoryL1;
    private Integer sourceCount;
    private String status;
    private String createdAt;
    private String content;
    private String summary;
    private String analyst;
    private Long projectId;
}
