package com.intelligence.platform.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("settings")
public class Setting {
    @TableId
    private String settingKey;
    private String value;
    private String description;
}
