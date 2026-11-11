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

    public String webReaderUserAgent() {
        String version = information.version();
        String product = version != null && version.matches("[A-Za-z0-9._+-]+") ? "AgenTeam/" + version : "AgenTeam";
        return "Mozilla/5.0 (compatible; " + product + "; +https://github.com/Stonewuu/agenteam)";
    }
}
