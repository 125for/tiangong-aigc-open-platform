package com.tiangong.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tiangong.task.client.BillingGateway;
import com.tiangong.task.config.TaskProperties;
import com.tiangong.task.dispatch.TaskDispatcher;
import com.tiangong.task.dto.Dtos.TaskSubmitReq;
import com.tiangong.task.entity.TaskEntity;
import com.tiangong.task.repo.TaskRepository;
import com.tiangong.task.service.WorkerExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
 * 故障注入用例（F6/F7）：
 *  - 推理 100% 失败 → 重试耗尽 → FAILED + 全额退款；
 *  - 任务超过 TTL → 扫描器 TIMEOUT + 退款（与 Worker 状态 CAS 互斥，无双重退款）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TaskFaultTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper om;

    @Autowired
    private TaskRepository taskRepo;

    @Autowired
    private TaskProperties props;

    @Autowired
    private WorkerExecutor workerExecutor;

    @MockitoBean
    private TaskDispatcher dispatcher;

    @MockitoBean
    private BillingGateway billingGateway;

    private long origLatency;
    private double origFailRate;
    private int origTtl;
    private int origMaxAttempts;

    @BeforeEach
    void saveProps() {
        origLatency = props.getEngine().getLatencyMs();
        origFailRate = props.getEngine().getFailRate();
        origTtl = props.getTtlSeconds();
        origMaxAttempts = props.getMaxAttempts();
        doAnswer(inv -> {
            workerExecutor.execute(inv.getArgument(0));
            return null;
        }).when(dispatcher).dispatch(anyString());
        when(billingGateway.hold(anyLong(), anyString(), anyInt()))
                .thenReturn(Map.of("status", "HELD"));
        when(billingGateway.settle(anyString())).thenReturn(Map.of("status", "SETTLED"));
        when(billingGateway.refund(anyString())).thenReturn(Map.of("status", "REFUNDED"));
    }

    @AfterEach
    void restoreProps() {
        props.getEngine().setLatencyMs(origLatency);
        props.getEngine().setFailRate(origFailRate);
        props.setTtlSeconds(origTtl);
        props.setMaxAttempts(origMaxAttempts);
    }

    private String submit(String prompt) throws Exception {
        MvcResult r = mvc.perform(post("/api/v1/tasks")
                        .header("X-App-Key", "ak_fault")
                        .header("X-App-User-Id", "2001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(
                                new TaskSubmitReq("TEXT_TO_IMAGE", prompt, null, null, null))))
                .andExpect(status().isOk())
                .andReturn();
        return om.readTree(r.getResponse().getContentAsString()).at("/data/taskNo").asText();
    }

    private boolean waitForStatus(String taskNo, String status, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            TaskEntity t = taskRepo.findByTaskNo(taskNo).orElse(null);
            if (t != null && status.equals(t.getStatus())) {
                return true;
            }
            Thread.sleep(100);
        }
        return false;
    }

    @Test
    void engineAlwaysFailThenRefund() throws Exception {
        props.getEngine().setFailRate(1.0);
        props.getEngine().setLatencyMs(50);
        props.setMaxAttempts(2);

        String taskNo = submit("will fail for sure");
        assertTrue(waitForStatus(taskNo, "FAILED", 15000), "重试耗尽后应 FAILED");

        TaskEntity t = taskRepo.findByTaskNo(taskNo).orElseThrow();
        assertEquals("ENGINE_FAILED", t.getErrorCode());
        assertEquals(2, t.getAttempts());
        verify(billingGateway, timeout(2000).times(1)).refund(eq(taskNo));
        verify(billingGateway, never()).settle(anyString());   // 失败绝不结算
    }

    @Test
    void taskExceedsTtlThenTimeoutRefund() throws Exception {
        props.setTtlSeconds(1);
        props.getEngine().setLatencyMs(15000);   // 慢推理，让超时扫描器先赢
        props.getEngine().setFailRate(0.0);

        String taskNo = submit("slow inference");
        assertTrue(waitForStatus(taskNo, "TIMEOUT", 15000), "超 TTL 应被扫描器终结: actual="
                + taskRepo.findByTaskNo(taskNo).map(TaskEntity::getStatus));

        verify(billingGateway, timeout(3000).times(1)).refund(eq(taskNo));
        // 晚到的 Worker 完成/失败尝试不会二次动账（状态 CAS 拒绝）
        verify(billingGateway, never()).settle(anyString());
    }
}
