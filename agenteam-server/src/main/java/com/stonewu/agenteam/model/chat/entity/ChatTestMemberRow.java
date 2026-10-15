package com.stonewu.agenteam.model.chat.entity;

import lombok.Data;
import java.time.Instant;

/** 仅供接收测试选择的有效成员，不包含平台账号和绑定凭据。 */
@Data
public class ChatTestMemberRow {
    private String id;
    private String name;
    private String username;
    private Instant createdAt;
}
