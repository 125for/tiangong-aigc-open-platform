package com.tiangong.account.service;

import com.tiangong.account.repo.RefreshTokenRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 独立事务执行家族撤销：调用方随后抛出的业务异常会回滚外层事务，
 * 而安全动作（撤销令牌家族）必须先行落库，因此必须 REQUIRES_NEW。
 */
@Component
@RequiredArgsConstructor
public class RefreshTokenRevoker {

    private final RefreshTokenRepository refreshTokenRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int revokeFamily(long familyId) {
        return refreshTokenRepository.revokeFamily(familyId);
    }
}
