package com.majstr.backend.repository;

import com.majstr.backend.entity.RegionalBaseline;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface RegionalBaselineRepository extends JpaRepository<RegionalBaseline, UUID> {

    /** Rebuilt from scratch on every weekly run. */
    @Modifying
    @Query("DELETE FROM RegionalBaseline")
    void deleteAllRows();
}
