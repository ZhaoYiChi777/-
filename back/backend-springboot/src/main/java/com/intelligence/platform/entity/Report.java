package com.intelligence.platform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("reports")
public class Report {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String title;
    private String reportType;
    private String categoryL1;
    private String categoryL2;
    private String projectName;
    private String status;
    private String uploadTime;
    private String summary;
    @TableField("`abstract`")
    private String abstract_;
    private String content;
    private String author;
    private Integer pages;
    private Integer sourceCount;
    private Long projectId;
}
