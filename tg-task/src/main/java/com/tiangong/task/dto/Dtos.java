package com.tiangong.task.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;

public final class Dtos {

    private Dtos() {
    }

    public record TaskSubmitReq(
            @NotBlank String type,
            @NotBlank @Size(max = 1024) String prompt,
            String paramsJson,
            @Size(max = 512) String callbackUrl,
            String appKey) {
    }

    public record TaskVO(String taskNo, String type, String prompt, String status,
                         int costCredits, int attempts, String resultUrl, String errorCode,
                         String errorMsg, LocalDateTime timeoutAt, LocalDateTime createdAt,
                         LocalDateTime updatedAt) {
    }

    public record TaskSubmitVO(String taskNo, String status, int costCredits,
                               LocalDateTime timeoutAt) {
    }

    public record TaskPageVO(List<TaskVO> items, int page, int size, long total) {
    }

    public record TaskEventVO(String fromStatus, String toStatus, String detail,
                              LocalDateTime createdAt) {
    }

    public record SinkRecordVO(long id, String payload, LocalDateTime receivedAt) {
    }

    public record WebhookPayload(String type, String taskNo, String status, String resultUrl,
                                 String errorCode, String finishedAt) {
    }
}
