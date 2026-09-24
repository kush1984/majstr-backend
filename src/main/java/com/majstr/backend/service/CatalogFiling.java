package com.majstr.backend.service;

import com.majstr.backend.entity.CatalogItem;
import com.majstr.backend.entity.CatalogTemplate;
import com.majstr.backend.entity.ItemType;
import com.majstr.backend.entity.Trade;
import com.majstr.backend.entity.Unit;
import com.majstr.backend.repository.CatalogTemplateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Which trade an estimate line belongs to, and what that trade calls the folder it sits in.
 *
 * <p><b>The problem this exists for.</b> {@code catalog_items} holds ONE row per (owner, name,
 * type, unit) — V118's rule — so a position two of the master's trades both ship is stored once,
 * under whichever trade claimed it first. Every door that builds an estimate line used to copy
 * that row's {@code trade} and {@code category} verbatim, which is right only when the master was
 * working in the trade that happened to claim it. Applying a PAINTER bundle produced «Шпаклювання
 * фінішне» filed under DRYWALL / «Оздоблення під фарбування» and «Прибирання приміщення» under
 * TILING, so a painting estimate grew category headers from two other trades — «якісь не зрозумілі
 * категорії з плитки, гіпсокартону».</p>
 *
 * <p><b>Why it is not only cosmetic.</b> The same stamp is what
 * {@code MaterialCalculatorService#normsFor} filters consumption norms by, and what
 * {@link com.majstr.backend.dto.MaterialCoverage} names the answered trades from. A line stamped
 * with a foreign trade reaches that trade's norms and not its own — on the master's live catalog
 * eight positions were unanswerable this way, «Фарбування фасаду» and «Декоративна штукатурка
 * фасаду короїд/баранець» among them, whose norms V138 had just shipped under PAINTER.</p>
 *
 * <p><b>The rule.</b> The caller says which trade the master was working in — the bundle's own
 * trade, or the branch of the picker tree he tapped in. If the shipped library files that exact
 * name+type+unit under that trade, the line takes THAT trade and THAT trade's category. Otherwise
 * nothing is assumed and the matched row's own filing stands: an answer we cannot source from the
 * library is a guess, and a guess here moves a position into a folder the master never chose.</p>
 *
 * <p>Only the filing moves. Price, unit, type, name and description still come from the master's
 * own catalog row — his money and his wording.</p>
 */
@Service
@RequiredArgsConstructor
public class CatalogFiling {

    private final CatalogTemplateRepository templateRepository;

    /** Where one line belongs: the trade it is filed under and that trade's folder. */
    public record Filing(Trade trade, String category) {}

    /**
     * {@code nameKey|TYPE|UNIT → the folder this trade files that position in}, for one trade.
     *
     * <p>Read per call rather than cached: {@code catalog_templates} is edited at runtime through
     * the admin screens as well as by migration, and a filing index that goes stale re-files a
     * master's line into a folder that no longer exists. One trade is ~50-230 rows.</p>
     *
     * <p>A trade the library ships nothing for — a custom trade, or OTHER — answers with an empty
     * map, which makes every lookup below fall through to the matched row's own filing.</p>
     */
    @Transactional(readOnly = true)
    public Map<String, String> categoriesUnder(Trade trade) {
        if (trade == null) {
            return Map.of();
        }
        Map<String, String> byKey = new HashMap<>();
        for (CatalogTemplate t : templateRepository.findByTradeInOrderBySortOrderAsc(List.of(trade))) {
            // First wins: the library's own order, so a name it ships twice keeps the earlier
            // folder — the same one the catalog board shows.
            byKey.putIfAbsent(key(t.getName(), t.getType(), t.getUnit()), t.getCategory());
        }
        return byKey;
    }

    /**
     * The filing a new line should carry, given the trade the master was working in and the
     * catalog row that priced it (nullable — a bundle line whose name this master does not stock
     * applies at 0 ₴ and still deserves to land in the right folder).
     */
    public static Filing fileUnder(Trade workingIn,
                                   Map<String, String> categoriesUnderWorkingTrade,
                                   String name,
                                   ItemType type,
                                   Unit unit,
                                   CatalogItem match) {
        Trade fallbackTrade = match == null ? null : match.getTrade();
        String fallbackCategory = match == null ? null : match.getCategory();
        String key = key(name, type, unit);
        if (workingIn == null || !categoriesUnderWorkingTrade.containsKey(key)) {
            return new Filing(fallbackTrade, fallbackCategory); // the library does not ship it here
        }
        // A library row with no folder of its own files the position «nowhere in particular», so
        // the stored folder is kept rather than «Без категорії» invented — the same rule the PWA's
        // catalog tree follows when it shows a shared position under a second trade.
        String category = categoriesUnderWorkingTrade.get(key);
        return new Filing(workingIn, category != null ? category : fallbackCategory);
    }

    /**
     * The join key. {@link NameKeys} is the product's one notion of «the same name» and the unit
     * is part of the identity — an m² position and its м.п. twin are different work (V112), so a
     * trade that ships only one of them must not re-file the other.
     */
    public static String key(String name, ItemType type, Unit unit) {
        return NameKeys.of(name) + "|" + type + "|" + unit;
    }
}
