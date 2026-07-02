package com.majstr.backend.repository;

import com.majstr.backend.entity.Partner;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface PartnerRepository extends JpaRepository<Partner, UUID> {

    /** Resolve a presented ref/promo code to an ACTIVE partner (case-insensitive). */
    Optional<Partner> findByCodeIgnoreCaseAndActiveTrue(String code);
}
