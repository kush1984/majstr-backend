package com.majstr.backend.service.importer;

import com.majstr.backend.entity.CatalogItem;
import com.majstr.backend.entity.CatalogTemplate;
import com.majstr.backend.entity.ItemType;
import com.majstr.backend.entity.Trade;
import com.majstr.backend.repository.CatalogItemRepository;
import com.majstr.backend.repository.CatalogTemplateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Which trade (and category) an imported position belongs under — the question «куди це все
 * розпихати» from the transfer flow.
 *
 * <p>The master's OWN catalog is asked first: if he already files this wording somewhere, that is
 * the answer. Then OUR library, where every position ships with a trade and a category. Both use
 * {@link CatalogMatcher} — the same exact-then-Dice match, with its refusal of a near tie, that
 * dictation trusts. A position matching neither is left unfiled (→ «Інше»), never guessed.</p>
 */
@Component
@RequiredArgsConstructor
public class TradeFiler {

    public record Filing(Trade trade, String category) {
        static final Filing NONE = new Filing(null, null);
    }

    private final CatalogItemRepository catalogItemRepository;
    private final CatalogTemplateRepository catalogTemplateRepository;

    /** One filing per {@code (name, type)}, in order; {@link Filing#NONE} where nothing matched. */
    @Transactional(readOnly = true)
    public List<Filing> file(UUID ownerId, List<Position> positions) {
        Map<ItemType, List<CatalogItem>> own = byType(catalogItemRepository.findByOwnerIdOrderByNameAsc(ownerId).stream()
                .filter(i -> i.getTrade() != null && i.getTrade() != Trade.OTHER).toList());
        Map<ItemType, List<CatalogItem>> library = byType(catalogTemplateRepository.findAll().stream()
                .map(TradeFiler::asItem).toList());
        List<Filing> out = new ArrayList<>(positions.size());
        for (Position p : positions) {
            Optional<CatalogItem> hit = CatalogMatcher.match(p.name(), own.getOrDefault(p.type(), List.of()));
            if (hit.isEmpty()) {
                hit = CatalogMatcher.match(p.name(), library.getOrDefault(p.type(), List.of()));
            }
            out.add(hit.map(i -> new Filing(i.getTrade(), i.getCategory())).orElse(Filing.NONE));
        }
        return out;
    }

    public record Position(String name, ItemType type) {}

    private static Map<ItemType, List<CatalogItem>> byType(List<CatalogItem> items) {
        Map<ItemType, List<CatalogItem>> map = new EnumMap<>(ItemType.class);
        for (CatalogItem item : items) {
            map.computeIfAbsent(item.getType(), t -> new ArrayList<>()).add(item);
        }
        return map;
    }

    /** A library row in the shape the matcher reads — transient, never saved. */
    private static CatalogItem asItem(CatalogTemplate t) {
        return CatalogItem.builder().id(t.getId()).name(t.getName()).type(t.getType()).unit(t.getUnit())
                .trade(t.getTrade()).category(t.getCategory()).build();
    }
}
