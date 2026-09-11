package com.majstr.backend.repository;

import com.majstr.backend.entity.MasterMaterialPref;
import com.majstr.backend.entity.MaterialPrefKey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface MasterMaterialPrefRepository extends JpaRepository<MasterMaterialPref, UUID> {

    List<MasterMaterialPref> findByUserId(UUID userId);

    Optional<MasterMaterialPref> findByUserIdAndPrefKey(UUID userId, MaterialPrefKey prefKey);
}
