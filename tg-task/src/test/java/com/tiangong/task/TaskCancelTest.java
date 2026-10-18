package com.tiangong.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tiangong.task.client.BillingGateway;
import com.tiangong.task.dispatch.TaskDispatcher;
import com.tiangong.task.dto.Dtos.TaskSubmitReq;
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

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 取消链路（F7 补充）：PENDING 任务可取消并自动退款；派发器置空使任务停留 PENDING。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TaskCancelTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper om;

    @MockitoBean
    private TaskDispatcher dispatcher;   // 不 stub → 任务停留在 PENDING

    @MockitoBean
    private BillingGateway billingGateway;

    @BeforeEach
    void stubBilling() {
        when(billingGateway.hold(anyLong(), anyString(), anyInt()))
                .thenReturn(Map.of("status", "HELD"));
        when(billingGateway.refund(anyString())).thenReturn(Map.of("status", "REFUNDED"));
    }

    @Test
    void cancelPendingTaskRefunds() throws Exception {
        MvcResult r = mvc.perform(post("/api/v1/tasks")
                        .header("X-App-Key", "ak_cancel")
                        .header("X-App-User-Id", "3001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(
                                new TaskSubmitReq("TTS", "hello world", null, null, null))))
                .andExpect(status().isOk())
                .andReturn();
        String taskNo = om.readTree(r.getResponse().getContentAsString())
                .at("/data/taskNo").asText();

        mvc.perform(post("/api/v1/tasks/{no}/cancel", taskNo)
                        .header("X-App-User-Id", "3001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELED"));

        verify(billingGateway, timeout(2000).times(1)).refund(eq(taskNo));
        verify(billingGateway, never()).settle(anyString());

        // 再次取消 → 状态机拒绝
        mvc.perform(post("/api/v1/tasks/{no}/cancel", taskNo)
                        .header("X-App-User-Id", "3001"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(0xC0201));
    }
}
