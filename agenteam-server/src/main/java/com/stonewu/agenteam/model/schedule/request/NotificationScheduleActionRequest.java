package com.stonewu.agenteam.model.schedule.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.hibernate.validator.constraints.UniqueElements;

import java.util.List;

/** 接收人是保存时明确选择的成员，不支持全员或动态部门表达式。 */
public record NotificationScheduleActionRequest(@NotBlank @Size(max = 100) String title, @NotNull @Size(max = 500) String body,
                                                @NotEmpty @Size(max = 50) List<@NotNull @Valid Recipient> recipients) {
    public record Recipient(@NotBlank @Size(max = 100) String userId,
                            @NotNull @Size(max = 10) @UniqueElements List<@NotBlank @Size(max = 100) String> connectionIds) {
    }
}
