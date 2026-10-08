package com.stonewu.agenteam.service.workspace;

import com.google.re2j.Pattern;
import com.stonewu.agenteam.service.http.ApiException;
import org.springframework.http.HttpStatus;

/**
 * 文件名通配符只参与匹配，不传给命令解释器或操作系统路径展开。
 */
public final class WorkspaceFileGlob {
    private final Pattern pattern;
    private final boolean fullPath;

    public WorkspaceFileGlob(String glob) {
        if (glob == null || glob.isBlank() || glob.length() > 128) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "FILE_GLOB_INVALID", "文件名筛选不正确。");
        }
        fullPath = glob.contains("/");
        StringBuilder expression = new StringBuilder("^");
        for (int index = 0; index < glob.length(); index++) {
            char c = glob.charAt(index);
            if (c == '*') {
                boolean recursive = index + 1 < glob.length() && glob.charAt(index + 1) == '*';
                expression.append(recursive ? ".*" : "[^/]*");
                if (recursive) {
                    index++;
                }
            } else if (c == '?') {
                expression.append("[^/]");
            } else {
                expression.append(Pattern.quote(Character.toString(c)));
            }
        }
        pattern = Pattern.compile(expression.append('$').toString());
    }

    public boolean matches(String path) {
        return pattern.matcher(fullPath ? path : path.substring(path.lastIndexOf('/') + 1)).matches();
    }
}
