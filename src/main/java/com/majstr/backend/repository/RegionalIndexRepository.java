package com.majstr.backend.repository;

import com.majstr.backend.entity.Region;
import com.majstr.backend.entity.RegionalIndex;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

@Repository
public interface RegionalIndexRepository extends JpaRepository<RegionalIndex, Region> {

    /** Rebuilt from scratch on every weekly run. */
    @Modifying
    @Query("DELETE FROM RegionalIndex")
    void deleteAllRows();
}
