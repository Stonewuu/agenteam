package com.stonewu.agenteam.model.test.source;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.math.BigInteger;

/**
 * 每个隔离测试库中独立创建的外部来源，覆盖无符号整数与高精度小数。
 */
@Getter
@Setter
@TableName("p05_source_rows")
public class ApiSourceRow {
    @TableId(type = IdType.INPUT)
    private BigInteger id;
    private BigDecimal amount;
    private String title;
    private Boolean enabled;
}
