package com.stonewu.agenteam.model.test.source;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 只读连接验证使用的简单外部表。
 */
@Getter
@Setter
@TableName("source_rows")
public class SourceRow {
    @TableId(type = IdType.INPUT)
    private Long id;
    private String title;
}
