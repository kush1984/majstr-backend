package com.majstr.backend.repository;

import com.majstr.backend.entity.CatalogItem;
import com.majstr.backend.entity.ItemType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface CatalogItemRepository extends JpaRepository<CatalogItem, UUID> {

    List<CatalogItem> findByOwnerIdOrderByNameAsc(UUID ownerId);

    List<CatalogItem> findByOwnerIdAndTypeOrderByNameAsc(UUID ownerId, ItemType type);
}
