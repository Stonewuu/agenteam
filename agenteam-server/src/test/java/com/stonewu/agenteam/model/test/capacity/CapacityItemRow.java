package com.stonewu.agenteam.model.test.capacity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 容量测试关联生成所用的第二张临时数字表。
 */
@Getter
@Setter
@TableName("capacity_item")
public class CapacityItemRow {
    @TableId(type = IdType.INPUT)
    private Integer n;
}
