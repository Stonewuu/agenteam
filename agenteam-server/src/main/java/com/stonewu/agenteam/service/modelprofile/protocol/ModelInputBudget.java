package com.stonewu.agenteam.service.modelprofile.protocol;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.service.http.ApiException;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ToolSchema;
import org.springframework.http.HttpStatus;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;

/** 在开发库生成请求字符串和字节副本之前检查输入，不额外保存整份序列化内容。 */
public final class ModelInputBudget {
    private ModelInputBudget() {
    }

    public static void requireFits(ObjectMapper json, List<Msg> messages, List<ToolSchema> tools, long maximum) {
        var counter = new Counter(maximum);
        try {
            json.writeValue(counter, messages);
            json.writeValue(counter, tools);
        } catch (IOException failure) {
            if (counter.exceeded) {
                var safe = new IOException("模型输入超过内存预算");
                safe.setStackTrace(failure.getStackTrace());
                throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "MODEL_INPUT_TOO_LARGE",
                    "本次对话内容过大，已保留原始内容，请缩小输入或新建对话后重试。", safe);
            }
            var safe = new IllegalStateException("模型输入无法编码。");
            safe.setStackTrace(failure.getStackTrace());
            throw safe;
        }
    }

    private static final class Counter extends OutputStream {
        private final long maximum;
        private long count;
        private boolean exceeded;

        private Counter(long maximum) {
            this.maximum = maximum;
        }

        @Override
        public void write(int value) throws IOException {
            add(1);
        }

        @Override
        public void write(byte[] value, int offset, int length) throws IOException {
            add(length);
        }

        private void add(int length) throws IOException {
            count += length;
            if (count > maximum) {
                exceeded = true;
                throw new IOException("模型输入超过内存预算");
            }
        }
    }
}
