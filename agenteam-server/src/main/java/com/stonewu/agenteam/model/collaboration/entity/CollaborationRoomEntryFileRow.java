package com.stonewu.agenteam.model.collaboration.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/** 保存发起人发送这一条消息时选定的原文件及其内容摘要。 */
@Getter
@Setter
@TableName("collaboration_room_entry_file")
public class CollaborationRoomEntryFileRow {
    @TableId(type = IdType.INPUT)
    private String id;
    private String enterpriseId;
    private String roomId;
    private String entryId;
    private String fileId;
    private Integer ordinal;
    private String sha256;
}
