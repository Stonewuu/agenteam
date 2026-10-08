package com.stonewu.agenteam.capacity;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.stonewu.agenteam.mapper.test.capacity.CapacityItemFixtureMapper;
import com.stonewu.agenteam.mapper.test.capacity.CapacityNumberFixtureMapper;
import com.stonewu.agenteam.mapper.test.capacity.CapacitySeedMapper;
import com.stonewu.agenteam.model.test.capacity.CapacityItemRow;
import com.stonewu.agenteam.model.test.capacity.CapacityNumberRow;
import com.stonewu.agenteam.support.IsolatedDatabase;
import com.stonewu.agenteam.support.MybatisTestDatabase;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 确认通用批处理使用创建临时表的同一条连接。
 */
class CapacityTemporaryTablesTest {
    @Test
    void bothTemporaryTablesRemainVisibleAcrossGenericBatches() throws Exception {
        try (var database = new IsolatedDatabase(); var connection = database.connection()) {
            var access = new MybatisTestDatabase(new SingleConnectionDataSource(connection, true));
            var setup = access.mapper(CapacitySeedMapper.class);
            setup.createNumbers();
            setup.createItems();
            var numbers = List.of(1, 2, 3).stream().map(value -> {
                var row = new CapacityNumberRow();
                row.setN(value);
                return row;
            }).toList();
            access.mapper(CapacityNumberFixtureMapper.class).insert(numbers, 2);
            access.mapper(CapacityItemFixtureMapper.class).insert(numbers.stream().map(number -> {
                var row = new CapacityItemRow();
                row.setN(number.getN());
                return row;
            }).toList(), 2);
            assertEquals(3L, access.mapper(CapacityNumberFixtureMapper.class).selectCount(new LambdaQueryWrapper<CapacityNumberRow>()));
            assertEquals(List.of(1, 2, 3), access.mapper(CapacityItemFixtureMapper.class)
                .selectList(new LambdaQueryWrapper<CapacityItemRow>().orderByAsc(CapacityItemRow::getN))
                .stream().map(CapacityItemRow::getN).toList());
        }
    }
}
