package com.tiangong.account;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tiangong.account.dto.Dtos.AppCreateReq;
import com.tiangong.account.dto.Dtos.LoginReq;
import com.tiangong.account.dto.Dtos.LogoutReq;
import com.tiangong.account.dto.Dtos.RefreshReq;
import com.tiangong.account.dto.Dtos.RegisterReq;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 账号域集成测试：注册/登录/令牌轮换与重放撤销（F14）/应用生命周期与密钥管理。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AccountFlowTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper om;

    private JsonNode postJson(String url, Object body) throws Exception {
        return postJson(url, body, null);
    }

    private JsonNode postJson(String url, Object body, String bearer) throws Exception {
        var req = post(url).contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(body));
        if (bearer != null) {
            req.header("Authorization", "Bearer " + bearer);
        }
        MvcResult r = mvc.perform(req).andExpect(status().isOk()).andReturn();
        return om.readTree(r.getResponse().getContentAsString());
    }

    @Test
    void registerLoginRefreshFlow() throws Exception {
        String email = "u" + System.nanoTime() + "@t.cn";
        JsonNode login = postJson("/api/v1/auth/register", new RegisterReq(email, "p@ssw0rd123", "tester"));
        long uid = login.at("/data/userId").asLong();
        assertTrue(uid > 0);
        String refresh1 = login.at("/data/tokens/refreshToken").asText();
        String access1 = login.at("/data/tokens/accessToken").asText();
        assertFalse(access1.isBlank());

        // Bearer 访问受保护接口
        mvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + access1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.email").value(email));

        // 重复注册 → 409
        mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(new RegisterReq(email, "p@ssw0rd123", "x"))))
                .andExpect(status().isConflict());

        // 刷新轮换：旧 RT 立即失效
        JsonNode pair2 = postJson("/api/v1/auth/refresh", new RefreshReq(refresh1));
        String refresh2 = pair2.at("/data/tokens/refreshToken").asText();
        assertNotEquals(refresh1, refresh2);
        mvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(new RefreshReq(refresh1))))
                .andExpect(status().isUnauthorized());

        // 重放已用 RT → 家族撤销：refresh2 也随之失效
        mvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(new RefreshReq(refresh1))))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(new RefreshReq(refresh2))))
                .andExpect(status().isUnauthorized());

        // 登出后 RT 失效；家族撤销不影响新登录链路
        JsonNode login3 = postJson("/api/v1/auth/login", new LoginReq(email, "p@ssw0rd123"));
        String refresh3 = login3.at("/data/tokens/refreshToken").asText();
        String access3 = login3.at("/data/tokens/accessToken").asText();
        postJson("/api/v1/auth/logout", new LogoutReq(refresh3), access3);
        mvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(new RefreshReq(refresh3))))
                .andExpect(status().isUnauthorized());
        JsonNode login4 = postJson("/api/v1/auth/login", new LoginReq(email, "p@ssw0rd123"));
        postJson("/api/v1/auth/refresh", new RefreshReq(login4.at("/data/tokens/refreshToken").asText()));
    }

    @Test
    void appLifecycleAndInternalApi() throws Exception {
        String email = "dev" + System.nanoTime() + "@t.cn";
        JsonNode login = postJson("/api/v1/auth/register", new RegisterReq(email, "p@ssw0rd123", "dev"));
        String access = login.at("/data/tokens/accessToken").asText();

        // 创建应用：明文 secret 仅出现一次
        MvcResult created = mvc.perform(post("/api/v1/apps")
                        .header("Authorization", "Bearer " + access)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(new AppCreateReq("my-first-app", null))))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode app = om.readTree(created.getResponse().getContentAsString()).at("/data");
        String appKey = app.get("appKey").asText();
        String secret1 = app.get("appSecret").asText();
        assertFalse(secret1.isBlank());

        // 列表中不出现明文 secret（字段整体省略）
        mvc.perform(get("/api/v1/apps").header("Authorization", "Bearer " + access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].appKey").value(appKey))
                .andExpect(jsonPath("$.data[0].appSecret").doesNotExist());

        // 他人应用禁止访问
        JsonNode other = postJson("/api/v1/auth/register",
                new RegisterReq("x" + System.nanoTime() + "@t.cn", "p@ssw0rd123", "x"));
        mvc.perform(post("/api/v1/apps/{k}/reset-secret", appKey)
                        .header("Authorization", "Bearer " + other.at("/data/tokens/accessToken").asText()))
                .andExpect(status().isForbidden());

        // 重置后获得新 secret
        MvcResult reset = mvc.perform(post("/api/v1/apps/{k}/reset-secret", appKey)
                        .header("Authorization", "Bearer " + access))
                .andExpect(status().isOk())
                .andReturn();
        String secret2 = om.readTree(reset.getResponse().getContentAsString()).at("/data/appSecret").asText();
        assertNotEquals(secret1, secret2);

        // internal 接口：令牌错误 401；正确则返回可验签的 secret
        mvc.perform(get("/internal/v1/apps/{k}", appKey))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/internal/v1/apps/{k}", appKey).header("X-Internal-Token", "test-internal-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.secret").value(secret2))
                .andExpect(jsonPath("$.data.userId").value(login.at("/data/userId").asLong()));

        mvc.perform(get("/internal/v1/users/{id}", login.at("/data/userId").asLong())
                        .header("X-Internal-Token", "test-internal-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));
    }

    @Test
    void invalidInputRejected() throws Exception {
        mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"not-an-email\",\"password\":\"123\",\"nickname\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(0xA0101));
    }
}
