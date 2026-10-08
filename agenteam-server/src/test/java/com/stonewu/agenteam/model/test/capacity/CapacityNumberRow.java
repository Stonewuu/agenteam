package com.stonewu.agenteam.model.test.capacity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 容量测试临时数字表，所有操作使用同一连接。
 */
@Getter
@Setter
@TableName("capacity_number")
public class CapacityNumberRow {
    @TableId(type = IdType.INPUT)
    private Integer n;
}
