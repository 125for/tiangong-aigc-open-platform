package com.tiangong.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tiangong.common.errcode.ErrorCode;
import com.tiangong.common.exception.BizException;
import com.tiangong.task.client.BillingGateway;
import com.tiangong.task.dispatch.TaskDispatcher;
import com.tiangong.task.dto.Dtos.TaskSubmitReq;
import com.tiangong.task.repo.TaskRepository;
import com.tiangong.task.service.WorkerExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 任务主链路（F1 幂等 / 预扣-结算 / 状态审计）：
 * 派发器 mock 为同步执行 WorkerExecutor，使异步链路在测试中确定性完成。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TaskFlowTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper om;

    @Autowired
    private TaskRepository taskRepo;

    @Autowired
    private WorkerExecutor workerExecutor;

    @MockitoBean
    private TaskDispatcher dispatcher;

    @MockitoBean
    private BillingGateway billingGateway;

    private static final long UID = 1001L;

    @BeforeEach
    void wireSyncDispatch() {
        doAnswer(inv -> {
            workerExecutor.execute(inv.getArgument(0));
            return null;
        }).when(dispatcher).dispatch(anyString());
        when(billingGateway.hold(anyLong(), anyString(), anyInt()))
                .thenReturn(Map.of("status", "HELD"));
        when(billingGateway.settle(anyString())).thenReturn(Map.of("status", "SETTLED"));
        when(billingGateway.refund(anyString())).thenReturn(Map.of("status", "REFUNDED"));
    }

    private boolean waitForStatus(String taskNo, String status, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (taskRepo.findByTaskNo(taskNo).map(t -> status.equals(t.getStatus())).orElse(false)) {
                return true;
            }
            Thread.sleep(100);
        }
        return false;
    }

    private JsonNode submit(String idemKey, String prompt) throws Exception {
        MvcResult r = mvc.perform(post("/api/v1/tasks")
                        .header("X-App-Key", "ak_test")
                        .header("X-App-User-Id", String.valueOf(UID))
                        .header("Idempotency-Key", idemKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(
                                new TaskSubmitReq("TEXT_TO_IMAGE", prompt, null, null, null))))
                .andExpect(status().isOk())
                .andReturn();
        return om.readTree(r.getResponse().getContentAsString()).at("/data");
    }

    @Test
    void submitIdempotentThenSucceedAndSettle() throws Exception {
        String key = "idem-" + System.nanoTime();
        JsonNode first = submit(key, "a cinematic dog on mars");
        String taskNo = first.get("taskNo").asText();
        assertEquals("PENDING", first.get("status").asText());
        assertEquals(10, first.get("costCredits").asInt());

        // 同键重放：同一 taskNo、不再预扣（幂等键回放）
        JsonNode second = submit(key, "a cinematic dog on mars");
        JsonNode third = submit(key, "a cinematic dog on mars");
        assertEquals(taskNo, second.get("taskNo").asText());
        assertEquals(taskNo, third.get("taskNo").asText());
        verify(billingGateway, times(1)).hold(eq(UID), eq(taskNo), eq(10));

        // 异步执行到终态 SUCCEEDED，settle 恰一次、无退款
        assertTrue(waitForStatus(taskNo, "SUCCEEDED", 10000), "任务应到达 SUCCEEDED");
        verify(billingGateway, timeout(2000).times(1)).settle(eq(taskNo));
        verify(billingGateway, never()).refund(anyString());

        // 详情与审计事件（创建→RUNNING→SUCCEEDED 共 3 条）
        mvc.perform(get("/api/v1/tasks/{no}", taskNo).header("X-App-User-Id", String.valueOf(UID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.data.resultUrl").isNotEmpty());
        mvc.perform(get("/api/v1/tasks/{no}/events", taskNo)
                        .header("X-App-User-Id", String.valueOf(UID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(3));

        // 幂等键但请求体不同 → 拒绝
        mvc.perform(post("/api/v1/tasks")
                        .header("X-App-Key", "ak_test")
                        .header("X-App-User-Id", String.valueOf(UID))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(
                                new TaskSubmitReq("TEXT_TO_IMAGE", "DIFFERENT PROMPT", null, null, null))))
                .andExpect(status().isBadRequest());

        // 他人任务禁止访问
        mvc.perform(get("/api/v1/tasks/{no}", taskNo).header("X-App-User-Id", "1002"))
                .andExpect(status().isForbidden());
    }

    @Test
    void invalidTypeAndInsufficientCreditsRejected() throws Exception {
        mvc.perform(post("/api/v1/tasks")
                        .header("X-App-Key", "ak_test")
                        .header("X-App-User-Id", String.valueOf(UID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(
                                new TaskSubmitReq("NOT_A_TYPE", "x", null, null, null))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value(0xC0202));
        verify(billingGateway, never()).hold(anyLong(), anyString(), anyInt());

        // 积分不足透传（billing 409 B0301 → 409 B0301），且无任务落库
        when(billingGateway.hold(anyLong(), anyString(), anyInt()))
                .thenThrow(new BizException(ErrorCode.INSUFFICIENT_CREDITS));
        mvc.perform(post("/api/v1/tasks")
                        .header("X-App-Key", "ak_test")
                        .header("X-App-User-Id", String.valueOf(UID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(
                                new TaskSubmitReq("TTS", "hello", null, null, null))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(0xB0301));
        assertEquals(0, taskRepo.findAll().stream()
                .filter(t -> t.getUserId() == UID && "TTS".equals(t.getType())).count());
        Mockito.reset(billingGateway);
    }
}
