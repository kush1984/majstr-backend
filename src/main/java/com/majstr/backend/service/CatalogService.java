package com.majstr.backend.service;

import com.majstr.backend.dto.CatalogItemRequest;
import com.majstr.backend.dto.CatalogItemResponse;
import com.majstr.backend.entity.CatalogItem;
import com.majstr.backend.entity.ItemType;
import com.majstr.backend.entity.User;
import com.majstr.backend.exception.ResourceNotFoundException;
import com.majstr.backend.repository.CatalogItemRepository;
import com.majstr.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CatalogService {

    private final CatalogItemRepository catalogRepository;
    private final UserRepository userRepository;

    @Transactional
    public CatalogItemResponse create(CatalogItemRequest req, UUID ownerId) {
        User owner = userRepository.getReferenceById(ownerId);
        CatalogItem item = CatalogItem.builder()
                .owner(owner)
                .name(req.name().trim())
                .type(req.type())
                .unit(req.unit())
                .defaultPrice(req.defaultPrice())
                .build();
        return CatalogItemResponse.from(catalogRepository.save(item));
    }

    @Transactional(readOnly = true)
    public List<CatalogItemResponse> listForOwner(UUID ownerId, ItemType type) {
        List<CatalogItem> items = type == null
                ? catalogRepository.findByOwnerIdOrderByNameAsc(ownerId)
                : catalogRepository.findByOwnerIdAndTypeOrderByNameAsc(ownerId, type);
        return items.stream().map(CatalogItemResponse::from).toList();
    }

    @Transactional
    public CatalogItemResponse update(UUID id, CatalogItemRequest req, UUID ownerId) {
        CatalogItem item = loadOwned(id, ownerId);
        item.setName(req.name().trim());
        item.setType(req.type());
        item.setUnit(req.unit());
        item.setDefaultPrice(req.defaultPrice());
        return CatalogItemResponse.from(item);
    }

    @Transactional
    public void delete(UUID id, UUID ownerId) {
        CatalogItem item = loadOwned(id, ownerId);
        catalogRepository.delete(item);
    }

    CatalogItem loadOwned(UUID id, UUID ownerId) {
        CatalogItem item = catalogRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Catalog item not found: " + id));
        if (!item.getOwner().getId().equals(ownerId)) {
            throw new AccessDeniedException("Catalog item does not belong to the current user");
        }
        return item;
    }
}
