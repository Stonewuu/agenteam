package com.stonewu.agenteam.mapper.data;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.stonewu.agenteam.model.data.entity.DataRecordRow;
import com.stonewu.agenteam.model.data.entity.DataRow;
import org.apache.ibatis.annotations.Mapper;

import java.time.Instant;
import java.util.List;

/**
 * 新版数据分批完整写入，集合当前版本由同一事务最后切换。
 */
@Mapper
public interface DataRecordMapper extends BaseMapper<DataRecordRow> {
    default void append(String enterprise, String collection, int generation, List<DataRow> rows, Instant now) {
        for (int start = 0; start < rows.size(); start += 100) {
            var batch = rows.subList(start, Math.min(start + 100, rows.size())).stream().map(value -> {
                var row = new DataRecordRow();
                row.setEnterpriseId(enterprise);
                row.setCollectionId(collection);
                row.setGeneration(generation);
                row.setRowNo(value.row());
                row.setValuesJson(value.values().toString());
                row.setCreatedAt(now);
                return row;
            }).toList();
            insert(batch, 100);
        }
    }


}
