package com.tiangong.task.schedule;

import com.tiangong.task.repo.WebhookDeliveryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;

/**
 * Webhook 投递中继：POST 应用回调地址，2xx 视为成功；
 * 指数退避重试，超限转 FAILED（人工出口，可查投递记录）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WebhookRelay {

    private static final int BATCH = 10;

    private final WebhookDeliveryRepository deliveryRepo;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    @Scheduled(fixedDelayString = "${tg.task.webhook-interval-ms:3000}", initialDelay = 2000)
    public void relay() {
        try {
            relayOnce();
        } catch (Exception e) {
            log.error("[webhook-relay] sweep failed", e);
        }
    }

    @Transactional
    void relayOnce() {
        var due = deliveryRepo.pickDue(LocalDateTime.now(), PageRequest.of(0, BATCH));
        for (var d : due) {
            int code;
            String error = null;
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(d.getUrl()))
                        .timeout(Duration.ofSeconds(5))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(d.getPayloadJson()))
                        .build();
                HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                code = resp.statusCode();
            } catch (Exception e) {
                code = -1;
                error = String.valueOf(e.getMessage());
            }
            if (code >= 200 && code < 300) {
                deliveryRepo.advance(d.getId(), "SUCCESS", null, code, LocalDateTime.now());
            } else {
                int next = d.getAttempts() + 1;
                String status = next >= d.getMaxAttempts() ? "FAILED" : "PENDING";
                long backoffSec = 1L << Math.min(next, 5);
                deliveryRepo.advance(d.getId(), status, error, code,
                        LocalDateTime.now().plusSeconds(backoffSec));
                log.warn("[webhook-relay] delivery {} attempt {} → {} code={}",
                        d.getId(), next, status, code);
            }
        }
    }
}
