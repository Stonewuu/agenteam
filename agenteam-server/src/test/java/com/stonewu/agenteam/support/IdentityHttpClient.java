package com.stonewu.agenteam.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 通过真实会话和请求防伪校验调用身份接口，不绕过过滤器。
 */
public class IdentityHttpClient {
    public record Session(Cookie cookie, String csrf) {
    }

    private final MockMvc mvc;
    private final ObjectMapper json;

    public IdentityHttpClient(MockMvc mvc, ObjectMapper json) {
        this.mvc = mvc;
        this.json = json;
    }

    public Session session(Cookie cookie) throws Exception {
        var request = get("/api/v1/auth/csrf");
        if (cookie != null) {
            request.cookie(cookie);
        }
        var result = mvc.perform(request).andExpect(status().isOk()).andReturn();
        Cookie next = result.getResponse().getCookie("SESSION");
        if (next == null) {
            next = cookie;
        }
        assertNotNull(next);
        return new Session(next, data(result).path("token").asText());
    }

    public ResultActions postJson(String path, Session session, Object body) throws Exception {
        return postJson(path, session, body, UUID.randomUUID().toString(), null);
    }

    public ResultActions postJson(String path, Session session, Object body, String key, String revision) throws Exception {
        var request = post(path).cookie(session.cookie()).header("Origin", "http://localhost:3000")
            .header("X-CSRF-Token", session.csrf()).header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON);
        if (revision != null) {
            request.header("If-Match", "\"" + revision + "\"");
        }
        return mvc.perform(request.content(json.writeValueAsBytes(body)));
    }

    public ResultActions getJson(String path, Session session) throws Exception {
        return mvc.perform(get(path).cookie(session.cookie()));
    }

    public JsonNode data(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString()).path("data");
    }
}
