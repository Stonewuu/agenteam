package com.stonewu.agenteam.mapper.execution;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

import com.github.yulichang.base.MPJBaseMapper;
import com.stonewu.agenteam.model.execution.entity.MessageFeedbackRow;
import org.apache.ibatis.annotations.Mapper;

/**
 * message_feedback 表的通用数据库操作。
 */
@Mapper
public interface MessageFeedbackTableMapper extends MPJBaseMapper<MessageFeedbackRow> {
    default int feedbackMessageFeedback(String enterprise, String user, String message) {
        return delete(new LambdaQueryWrapper<MessageFeedbackRow>().eq(MessageFeedbackRow::getEnterpriseId, enterprise)
            .eq(MessageFeedbackRow::getUserId, user).eq(MessageFeedbackRow::getMessageId, message));
    }
}
