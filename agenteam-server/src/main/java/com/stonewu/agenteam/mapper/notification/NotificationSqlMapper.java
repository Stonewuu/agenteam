package com.stonewu.agenteam.mapper.notification;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.github.yulichang.base.MPJBaseMapper;
import com.stonewu.agenteam.model.notification.entity.NotificationQueryRow;
import com.stonewu.agenteam.model.notification.entity.NotificationRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.sql.Timestamp;
import java.util.List;

/**
 * NotificationMapper 对应的数据库语句，参数绑定和查询结果均有明确类型。
 */
@Mapper
public interface NotificationSqlMapper extends MPJBaseMapper<NotificationRow> {
    default List<NotificationQueryRow> pageNotifications(String enterprise, String user, boolean unread, long through,
                                                         Long before, int limit) {
        var criteria = new LambdaQueryWrapper<NotificationRow>().orderByDesc(NotificationRow::getSequenceNo)
            .eq(NotificationRow::getEnterpriseId, enterprise).eq(NotificationRow::getUserId, user)
            .le(NotificationRow::getSequenceNo, through);
        if (unread) {
            criteria.isNull(NotificationRow::getReadAt);
        }
        if (before != null) {
            criteria.lt(NotificationRow::getSequenceNo, before);
        }
        long pageSize = limit;
        if (pageSize == 0) {
            return List.of();
        }
        return selectPage(new Page<NotificationRow>(1, pageSize, false), criteria).getRecords().stream()
            .map(storedRow -> {
                var mappedRow = new NotificationQueryRow();
                if (storedRow.getId() != null) {
                    mappedRow.setId(storedRow.getId());
                }
                if (storedRow.getSequenceNo() != null) {
                    mappedRow.setSequenceNo(storedRow.getSequenceNo());
                }
                if (storedRow.getCategory() != null) {
                    mappedRow.setCategory(storedRow.getCategory());
                }
                if (storedRow.getTitle() != null) {
                    mappedRow.setTitle(storedRow.getTitle());
                }
                if (storedRow.getBody() != null) {
                    mappedRow.setBody(storedRow.getBody());
                }
                if (storedRow.getTargetType() != null) {
                    mappedRow.setTargetType(storedRow.getTargetType());
                }
                if (storedRow.getTargetId() != null) {
                    mappedRow.setTargetId(storedRow.getTargetId());
                }
                if (storedRow.getReadAt() != null) {
                    mappedRow.setReadAt((storedRow.getReadAt() == null ? null : Timestamp.from(storedRow.getReadAt())));
                }
                if (storedRow.getCreatedAt() != null) {
                    mappedRow.setCreatedAt(
                        (storedRow.getCreatedAt() == null ? null : Timestamp.from(storedRow.getCreatedAt())));
                }
                return mappedRow;
            }).toList();
    }

    List<Long> lockSequence(@Param("enterprise") String enterprise, @Param("user") String user);

    default List<Long> unreadNotification(String enterprise, String user) {
        return List.of(selectCount(
            new LambdaQueryWrapper<NotificationRow>().eq(NotificationRow::getEnterpriseId, enterprise)
                .eq(NotificationRow::getUserId, user).isNull(NotificationRow::getReadAt)));
    }

    default List<NotificationQueryRow> findNotification(String enterprise, String user, String id, boolean lock) {
        if (lock) {
            return findNotificationLocked(enterprise, user, id, lock);
        }
        var criteria = new LambdaQueryWrapper<NotificationRow>().eq(NotificationRow::getEnterpriseId, enterprise)
            .eq(NotificationRow::getUserId, user).eq(NotificationRow::getId, id);
        return selectList(criteria).stream().map(storedRow -> {
            var mappedRow = new NotificationQueryRow();
            if (storedRow.getId() != null) {
                mappedRow.setId(storedRow.getId());
            }
            if (storedRow.getSequenceNo() != null) {
                mappedRow.setSequenceNo(storedRow.getSequenceNo());
            }
            if (storedRow.getCategory() != null) {
                mappedRow.setCategory(storedRow.getCategory());
            }
            if (storedRow.getTitle() != null) {
                mappedRow.setTitle(storedRow.getTitle());
            }
            if (storedRow.getBody() != null) {
                mappedRow.setBody(storedRow.getBody());
            }
            if (storedRow.getTargetType() != null) {
                mappedRow.setTargetType(storedRow.getTargetType());
            }
            if (storedRow.getTargetId() != null) {
                mappedRow.setTargetId(storedRow.getTargetId());
            }
            if (storedRow.getReadAt() != null) {
                mappedRow.setReadAt((storedRow.getReadAt() == null ? null : Timestamp.from(storedRow.getReadAt())));
            }
            if (storedRow.getCreatedAt() != null) {
                mappedRow.setCreatedAt(
                    (storedRow.getCreatedAt() == null ? null : Timestamp.from(storedRow.getCreatedAt())));
            }
            return mappedRow;
        }).toList();
    }

    List<NotificationQueryRow> findNotificationLocked(@Param("enterprise") String enterprise,
                                                      @Param("user") String user, @Param("id") String id,
                                                      @Param("lock") boolean lock);

    default int readNotification(Timestamp now, String enterprise, String user, String id) {
        return update(new LambdaUpdateWrapper<NotificationRow>().eq(NotificationRow::getEnterpriseId, enterprise)
            .eq(NotificationRow::getUserId, user).eq(NotificationRow::getId, id).isNull(NotificationRow::getReadAt)
            .set(NotificationRow::getReadAt, now));
    }

    default int readThroughNotification(Timestamp now, String enterprise, String user, long through) {
        return update(new LambdaUpdateWrapper<NotificationRow>().eq(NotificationRow::getEnterpriseId, enterprise)
            .eq(NotificationRow::getUserId, user).le(NotificationRow::getSequenceNo, through)
            .isNull(NotificationRow::getReadAt).set(NotificationRow::getReadAt, now));
    }

    default List<String> deliverNotification(String enterprise, String user, String eventKey) {
        var criteria = new LambdaQueryWrapper<NotificationRow>().select(NotificationRow::getId)
            .eq(NotificationRow::getEnterpriseId, enterprise).eq(NotificationRow::getUserId, user)
            .eq(NotificationRow::getEventKey, eventKey);
        return selectList(criteria).stream().map(storedRow -> storedRow.getId()).toList();
    }

    default int insertNotification(String id, String enterprise, String user, long sequence, String eventKey,
                                   String category, String title, String body, String targetType, String targetId,
                                   Timestamp now) {
        var databaseRow = new NotificationRow();
        databaseRow.setId(id);
        databaseRow.setEnterpriseId(enterprise);
        databaseRow.setUserId(user);
        databaseRow.setSequenceNo(sequence);
        databaseRow.setEventKey(eventKey);
        databaseRow.setCategory(category);
        databaseRow.setTitle(title);
        databaseRow.setBody(body);
        databaseRow.setTargetType(targetType);
        databaseRow.setTargetId(targetId);
        databaseRow.setCreatedAt((now == null ? null : now.toInstant()));
        return insert(databaseRow);
    }
}
