package com.tiangong.task.controller;

import com.tiangong.common.web.CurrentUser;
import com.tiangong.common.web.Result;
import com.tiangong.task.dto.Dtos.SinkRecordVO;
import com.tiangong.task.entity.WebhookSinkRecord;
import com.tiangong.task.repo.WebhookSinkRepository;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 测试用回调接收器：验收 Webhook 是否真实送达（入库可查）。 */
@RestController
@RequestMapping("/api/v1/webhooks/sink")
@RequiredArgsConstructor
public class WebhookSinkController {

    private final WebhookSinkRepository sinkRepo;

    @Value("${tg.jwt.secret}")
    private String jwtSecret;

    @PostMapping
    public Result<Void> receive(@RequestBody String body) {
        WebhookSinkRecord r = new WebhookSinkRecord();
        r.setPayloadJson(body);
        sinkRepo.save(r);
        return Result.ok();
    }

    @GetMapping("/records")
    public Result<List<SinkRecordVO>> records(HttpServletRequest request) {
        CurrentUser.userId(request, jwtSecret);   // 仅登录用户可查看
        return Result.ok(sinkRepo.findTop20ByOrderByIdDesc().stream()
                .map(r -> new SinkRecordVO(r.getId(), r.getPayloadJson(), r.getReceivedAt()))
                .toList());
    }
}
