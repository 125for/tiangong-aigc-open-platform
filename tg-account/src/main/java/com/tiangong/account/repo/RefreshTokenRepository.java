package com.tiangong.account.repo;

import com.tiangong.account.entity.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    @Modifying
    @Query("UPDATE RefreshToken rt SET rt.status = 'REVOKED' WHERE rt.familyId = :familyId AND rt.status <> 'REVOKED'")
    int revokeFamily(Long familyId);
}
