package com.stonewu.agenteam.service.workspace;

import com.stonewu.agenteam.model.system.entity.LimitValue;

import java.util.List;

/** Docker 使用省略配额或零容量表示不限，在此处转换业务配置。 */
public final class SandboxResourceArguments {
    private SandboxResourceArguments() {
    }

    public static void addCompute(List<String> arguments, int memoryMb, int cpus) {
        if (!LimitValue.unlimited(memoryMb)) {
            long bytes = LimitValue.bytes(memoryMb, 1048576);
            arguments.add("--memory=" + bytes);
            arguments.add("--memory-swap=" + bytes);
        }
        if (!LimitValue.unlimited(cpus)) {
            arguments.add("--cpus=" + cpus);
        }
    }

    public static String temporaryMount(int megabytes) {
        String size = LimitValue.unlimited(megabytes) ? "0" : megabytes + "m";
        return "--tmpfs=/tmp:rw,nosuid,nodev,size=" + size + ",mode=1777";
    }
}
