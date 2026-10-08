package com.stonewu.agenteam.model.auth.entity;

/** 账号扩展可限制的操作；原身份、权限和事务检查仍由各业务服务执行。 */
public enum AccountOperation {
    RESET_PASSWORD,
    CHANGE_PASSWORD,
    CHANGE_EMAIL,
    BIND_EXTERNAL_ACCOUNT,
    MANAGE_INTEGRATION,
    MANAGE_CHANNEL_DELIVERY,
    CREATE_INVITATION,
    ACCEPT_INVITATION,
    SELECT_ENTERPRISE_ADMINISTRATOR,
    EDIT_MEMBER,
    REMOVE_MEMBER
}
