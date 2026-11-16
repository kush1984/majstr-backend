package com.majstr.backend.repository;

import java.util.Optional;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;
import com.majstr.backend.entity.InboxItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface InboxItemRepository extends JpaRepository<InboxItem, UUID> {

    List<InboxItem> findByEntryIdOrderBySortOrderAsc(UUID entryId);

    List<InboxItem> findByEntryIdInOrderBySortOrderAsc(Collection<UUID> entryIds);

    /** The row lock that makes filing an item a one-time act: two taps on «Записати» write one record. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT i FROM InboxItem i WHERE i.id = :id")
    Optional<InboxItem> lockById(@Param("id") UUID id);
}
