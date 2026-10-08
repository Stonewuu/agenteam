package com.stonewu.agenteam.mapper.test.todo;

import com.github.yulichang.base.MPJBaseMapper;
import com.stonewu.agenteam.model.todo.entity.TodoItemRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * todo_item 的测试数据准备与实际数据库断言。
 */
@Mapper
public interface TodoItemFixtureMapper extends MPJBaseMapper<TodoItemRow> {
    List<Integer> todoApiConcurrentTransferAndTeamDepartureCannotLeaveAnOpenTodoOwnedOutsideItsTeamObject(@Param("args") Object... args);

}
