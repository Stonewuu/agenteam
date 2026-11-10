package com.stonewu.agenteam.service.http;

import com.stonewu.agenteam.model.http.response.ApplicationInfoResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.stereotype.Service;

/** 构建信息随当前应用打包，不读取前端版本或相邻源码。 */
@Service
public class ApplicationInfoService {
    private final ApplicationInfoResponse information;

    public ApplicationInfoService(ObjectProvider<BuildProperties> builds) {
        var build = builds.getIfAvailable();
        String version = build == null ? null : build.getVersion();
        information = new ApplicationInfoResponse(version == null || version.isBlank() ? null : version);
    }

    public ApplicationInfoResponse current() {
        return information;
    }
}
