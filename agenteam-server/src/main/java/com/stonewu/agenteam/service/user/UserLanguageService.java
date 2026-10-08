package com.stonewu.agenteam.service.user;

import com.stonewu.agenteam.mapper.user.UserPreferenceMapper;
import com.stonewu.agenteam.model.user.entity.UserLanguage;
import com.stonewu.agenteam.model.user.entity.UserPreference;
import org.springframework.stereotype.Service;

/**
 * 每次创建实际智能体时读取用户回复语言，不写入公开资源或会话消息。
 */
@Service
public class UserLanguageService {
    private final UserPreferenceMapper preferences;

    public UserLanguageService(UserPreferenceMapper preferences) {
        this.preferences = preferences;
    }

    public String instruction(String userId) {
        return preferences.find(userId).map(UserPreference::responseLanguage).flatMap(UserLanguage::find)
            .orElse(UserLanguage.SIMPLIFIED_CHINESE).instruction();
    }
}
