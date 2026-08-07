package com.intelligence.platform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("kg_entry_build_states")
public class KGEntryBuildState {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long projectId;
    private Long entryId;
    private String entryHash;
    private Long graphVersion;
    private Long nodeId;
    private String status;
    private String lastBuiltAt;
}
