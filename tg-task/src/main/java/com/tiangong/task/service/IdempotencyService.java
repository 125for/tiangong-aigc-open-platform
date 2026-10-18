package com.tiangong.task.service;

import com.tiangong.common.errcode.ErrorCode;
import com.tiangong.common.exception.BizException;
import com.tiangong.task.entity.IdempotencyKey;
import com.tiangong.task.repo.IdempotencyKeyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * API 幂等（L1 层）：
 *  begin   → BEGIN（首次）/ HIT_DONE(json)（回放）/ IN_FLIGHT（并发同键）
 *  complete→ DONE 并保存响应快照
 *  abort   → 删除记录（业务失败允许客户端重试）
 * 注意 REQUIRES_NEW：抢占幂等位必须独立提交，业务事务回滚不能影响幂等语义。
 */
@Service
@RequiredArgsConstructor
public class IdempotencyService {

    private final IdempotencyKeyRepository repo;

    public static final long TTL_HOURS = 24;

    public sealed interface BeginResult {
        record Begin() implements BeginResult {
        }

        record HitDone(String responseJson) implements BeginResult {
        }

        record InFlight() implements BeginResult {
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public BeginResult begin(String idemKey, String endpoint, String requestHash) {
        Optional<IdempotencyKey> found = repo.findByIdemKeyAndEndpoint(idemKey, endpoint);
        if (found.isPresent()) {
            IdempotencyKey key = found.get();
            if (key.getExpiresAt().isBefore(LocalDateTime.now())) {
                repo.delete(key);   // 过期键视为不存在，允许新一轮
                repo.flush();
            } else if ("DONE".equals(key.getStatus())) {
                if (!key.getRequestHash().equals(requestHash)) {
                    throw new BizException(ErrorCode.IDEMPOTENT_KEY_MISMATCH);
                }
                return new BeginResult.HitDone(key.getResponseJson());
            } else {
                return new BeginResult.InFlight();
            }
        }
        IdempotencyKey key = new IdempotencyKey();
        key.setIdemKey(idemKey);
        key.setEndpoint(endpoint);
        key.setRequestHash(requestHash);
        key.setStatus("PROCESSING");
        key.setExpiresAt(LocalDateTime.now().plusHours(TTL_HOURS));
        try {
            repo.saveAndFlush(key);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            return new BeginResult.InFlight();   // 并发抢占失败
        }
        return new BeginResult.Begin();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void complete(String idemKey, String endpoint, String responseJson) {
        repo.findByIdemKeyAndEndpoint(idemKey, endpoint).ifPresent(key -> {
            key.setStatus("DONE");
            key.setResponseJson(responseJson);
            repo.save(key);
        });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void abort(String idemKey, String endpoint) {
        repo.findByIdemKeyAndEndpoint(idemKey, endpoint)
                .filter(k -> "PROCESSING".equals(k.getStatus()))
                .ifPresent(repo::delete);
    }
}
