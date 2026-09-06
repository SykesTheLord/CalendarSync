package com.sykessec.calendarsync.repository;

import com.sykessec.calendarsync.entity.TotpRecoveryCode;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Every finder here is scoped by userId, per the repository-layer isolation
 * rule: a recovery code is a credential, and an unscoped lookup by hash would
 * let one user's code unlock another user's account.
 */
public interface TotpRecoveryCodeRepository extends JpaRepository<TotpRecoveryCode, Long> {

    List<TotpRecoveryCode> findAllByUserIdAndUsedAtIsNull(Long userId);

    long countByUserIdAndUsedAtIsNull(Long userId);

    void deleteAllByUserId(Long userId);
}
