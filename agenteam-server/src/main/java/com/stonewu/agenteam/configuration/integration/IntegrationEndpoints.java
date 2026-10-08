package com.stonewu.agenteam.configuration.integration;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.URI;

/**
 * 生产固定官方服务地址；测试通过构造器注入模拟服务器，不开放管理员自定义服务地址。
 */
@Component
public class IntegrationEndpoints {
    private final URI wecom;
    private final URI feishu;
    private final URI feishuAccounts;

    @Autowired
    public IntegrationEndpoints() {
        this(URI.create("https://qyapi.weixin.qq.com"), URI.create("https://open.feishu.cn"),
            URI.create("https://accounts.feishu.cn"));
    }

    public IntegrationEndpoints(URI wecom, URI feishu, URI feishuAccounts) {
        this.wecom = wecom;
        this.feishu = feishu;
        this.feishuAccounts = feishuAccounts;
    }

    public URI wecom(String path) {
        return wecom.resolve(path);
    }

    public URI feishu(String path) {
        return feishu.resolve(path);
    }

    public URI feishuAccounts(String path) {
        return feishuAccounts.resolve(path);
    }
}
