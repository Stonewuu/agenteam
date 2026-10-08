package com.stonewu.agenteam.mapper.user;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.stonewu.agenteam.model.user.entity.UserPreference;
import com.stonewu.agenteam.model.user.entity.UserPreferenceRow;
import org.apache.ibatis.annotations.Mapper;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 个人偏好按全局用户唯一保存，修改时验证读取版本。
 */
@Mapper
public interface UserPreferenceMapper extends BaseMapper<UserPreferenceRow> {
    default Optional<UserPreference> find(String userId) {
        return Optional.ofNullable(selectById(userId)).map(row -> new UserPreference(row.getUserId(), row.getTheme(),
            row.getTaskCompletionNotifications() != 0, row.getMemoryEnabled() != 0, row.getResponseLanguage(),
            row.getRevision()));
    }

    default boolean update(UserPreference preference, Instant now) {
        return updatePreference(preference, now) == 1;
    }

    default int updatePreference(UserPreference preference, Instant now) {
        return update(new LambdaUpdateWrapper<UserPreferenceRow>()
            .eq(UserPreferenceRow::getUserId, preference.userId())
            .eq(UserPreferenceRow::getRevision, preference.revision())
            .set(UserPreferenceRow::getTheme, preference.theme())
            .set(UserPreferenceRow::getTaskCompletionNotifications, preference.taskCompletionNotifications())
            .set(UserPreferenceRow::getMemoryEnabled, preference.memoryEnabled())
            .set(UserPreferenceRow::getResponseLanguage, preference.responseLanguage())
            .setIncrBy(UserPreferenceRow::getRevision, 1)
            .set(UserPreferenceRow::getUpdatedAt, now));
    }

    default List<Boolean> completionEnabledUserPreference(String user) {
        var criteria = new LambdaQueryWrapper<UserPreferenceRow>().select(
            UserPreferenceRow::getTaskCompletionNotifications).eq(UserPreferenceRow::getUserId, user);
        return selectList(criteria).stream().map(
                storedRow -> (storedRow.getTaskCompletionNotifications() != null && storedRow.getTaskCompletionNotifications() != 0))
            .toList();
    }
}
