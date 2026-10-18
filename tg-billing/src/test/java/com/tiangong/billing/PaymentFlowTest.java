package com.tiangong.billing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tiangong.billing.client.AccountClient;
import com.tiangong.billing.dto.Dtos.InternalUserVO;
import com.tiangong.billing.dto.Dtos.MockPayReq;
import com.tiangong.billing.dto.Dtos.OrderCreateReq;
import com.tiangong.billing.entity.OrderEntity;
import com.tiangong.billing.repo.OrderRepository;
import com.tiangong.billing.service.OrderService;
import com.tiangong.common.util.CryptoUtil;
import com.tiangong.common.util.JwtSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 订单→支付回调→发货 链路（F2 幂等核心）：
 *  - 回调重放 3 次只发货一次；
 *  - 验签失败/金额不符被拒；
 *  - 超时订单关闭后回调被拒。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PaymentFlowTest {

    private static final String JWT_SECRET = "unit-test-jwt-secret-key-32-bytes-ok!!!";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper om;

    @Autowired
    private OrderRepository orderRepo;

    @Autowired
    private OrderService orderService;

    @MockitoBean
    private AccountClient accountClient;

    private String token(long uid) {
        return JwtSupport.issueAccessToken(JWT_SECRET, uid, "u@t.cn", "n", 600);
    }

    @Test
    void orderPaidOnceUnderCallbackReplay() throws Exception {
        long uid = 777001;
        when(accountClient.getUser(anyLong()))
                .thenReturn(new com.tiangong.common.web.Result<>(0, "OK",
                        new InternalUserVO(uid, "ACTIVE"), null));

        // 下单（starter_pro：6000 积分订阅）
        MvcResult created = mvc.perform(post("/api/v1/orders")
                        .header("Authorization", "Bearer " + token(uid))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(new OrderCreateReq("starter_pro"))))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode order = om.readTree(created.getResponse().getContentAsString()).at("/data");
        String orderNo = order.get("orderNo").asText();
        assertEquals(9900, order.get("amountCents").asInt());

        // 渠道模拟器：铸造签名通知
        MvcResult minted = mvc.perform(post("/api/v1/payments/mock-pay")
                        .header("Authorization", "Bearer " + token(uid))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(new MockPayReq(orderNo))))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode mock = om.readTree(minted.getResponse().getContentAsString()).at("/data");
        String body = om.writeValueAsString(mock.get("callbackBody"));
        String sign = mock.get("signature").asText();

        // 首次回调：发货
        mvc.perform(post("/api/v1/payments/callback").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Channel-Sign", sign).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.delivered").value(true));
        mvc.perform(get("/api/v1/credits/me").header("Authorization", "Bearer " + token(uid)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.balance").value(6000));

        // 重放 3 次：delivered=false，积分不变
        for (int i = 0; i < 3; i++) {
            mvc.perform(post("/api/v1/payments/callback").contentType(MediaType.APPLICATION_JSON)
                            .header("X-Channel-Sign", sign).content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.delivered").value(false));
        }
        mvc.perform(get("/api/v1/credits/me").header("Authorization", "Bearer " + token(uid)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.balance").value(6000))
                .andExpect(jsonPath("$.data.held").value(0));

        // 流水恰 1 笔 GRANT
        mvc.perform(get("/api/v1/ledgers/me").header("Authorization", "Bearer " + token(uid)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].changeType").value("GRANT"));

        // 验签失败 / 金额篡改
        JsonNode cb = mock.get("callbackBody");
        mvc.perform(post("/api/v1/payments/callback").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Channel-Sign", "deadbeef").content(body))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/payments/callback").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Channel-Sign", CryptoUtil.hmacSha256Hex("test-channel-key",
                                cb.get("paymentNo").asText() + "|" + orderNo + "|" + 1))
                        .content(om.writeValueAsString(new com.tiangong.billing.dto.Dtos.PaymentCallbackReq(
                                cb.get("paymentNo").asText(), orderNo, 1, "MOCK"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void expiredOrderClosedAndRejectsCallback() throws Exception {
        long uid = 777002;
        when(accountClient.getUser(anyLong()))
                .thenReturn(new com.tiangong.common.web.Result<>(0, "OK",
                        new InternalUserVO(uid, "ACTIVE"), null));
        var order = orderService.createOrder(uid, "credits_500");
        OrderEntity e = orderRepo.findByOrderNo(order.orderNo()).orElseThrow();
        e.setExpiresAt(LocalDateTime.now().minusMinutes(1));
        orderRepo.save(e);

        assertEquals(1, orderService.closeExpiredOrders(), "应关闭 1 笔超时订单");
        assertEquals("CLOSED", orderService.getByOrderNo(uid, order.orderNo()).status());

        // 关闭订单的回调被拒
        mvc.perform(post("/api/v1/payments/callback").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Channel-Sign", CryptoUtil.hmacSha256Hex("test-channel-key",
                                "P1|" + order.orderNo() + "|" + order.amountCents()))
                        .content(om.writeValueAsString(new com.tiangong.billing.dto.Dtos.PaymentCallbackReq(
                                "P1", order.orderNo(), order.amountCents(), "MOCK"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(0xB0201));
    }
}
