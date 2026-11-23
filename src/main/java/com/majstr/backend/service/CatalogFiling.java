package com.majstr.backend.service;

import com.majstr.backend.entity.CatalogItem;
import com.majstr.backend.entity.CatalogItemSource;
import com.majstr.backend.entity.CatalogTemplate;
import com.majstr.backend.entity.EstimateItem;
import com.majstr.backend.entity.ItemType;
import com.majstr.backend.entity.Trade;
import com.majstr.backend.entity.Unit;
import com.majstr.backend.repository.CatalogTemplateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Which trade an estimate line belongs to, and what that trade calls the folder it sits in.
 *
 * <p><b>Every trade holds its own positions (V220).</b> {@code catalog_items} is keyed by
 * (owner, trade, name, type, unit): a position two of the master's trades both ship is a row in
 * each, in that trade's folder, at that trade's price. A line built from a catalog row therefore
 * takes the row's own trade and folder — no re-filing. What is left for this class is the lines
 * that arrive by NAME rather than by row: a bundle line, a typed line, an imported or dictated one.
 * For those the rules below say which of the master's rows the name means, and they never pick
 * between two trades on a hunch.</p>
 *
 * <p>Only the filing is decided here. Price, unit, type, name and description still come from the
 * master's own catalog row — his money and his wording.</p>
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
     * the admin screens as well as by migration, and a filing index that goes stale files a line
     * into a folder that no longer exists. One trade is ~50-230 rows.</p>
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
            // folder — the same one V220 filed the master's copy under.
            byKey.putIfAbsent(key(t.getName(), t.getType(), t.getUnit()), t.getCategory());
        }
        return byKey;
    }

    /**
     * The filing of a line a BUNDLE (or the training sample) names, in the trade it belongs to.
     *
     * <ol>
     *   <li>{@code match} is the master's own row of that trade → its folder: his arrangement.</li>
     *   <li>The library ships the name under that trade → that trade, the library's folder.</li>
     *   <li>Otherwise the name is not this trade's work at all (a master's own bundle may carry a
     *       line of another trade) → the matched row's own trade and folder, a fact and not a
     *       guess; with no row at all, nothing.</li>
     * </ol>
     *
     * <p>{@code match} is nullable — a bundle line whose name this master does not stock applies at
     * 0 ₴ and still deserves to land in the right folder.</p>
     */
    public static Filing fileUnder(Trade workingIn,
                                   Map<String, String> categoriesUnderWorkingTrade,
                                   String name,
                                   ItemType type,
                                   Unit unit,
                                   CatalogItem match) {
        if (workingIn != null && match != null && match.getTrade() == workingIn) {
            return new Filing(workingIn, match.getCategory());
        }
        Trade fallbackTrade = match == null ? null : match.getTrade();
        String fallbackCategory = match == null ? null : match.getCategory();
        String key = key(name, type, unit);
        if (workingIn == null || !categoriesUnderWorkingTrade.containsKey(key)) {
            return new Filing(fallbackTrade, fallbackCategory); // the library does not ship it here
        }
        return new Filing(workingIn, categoriesUnderWorkingTrade.get(key));
    }

    /**
     * Of the master's rows sharing one name, the one that prices a line in {@code workingIn}: that
     * trade's own row, else one of a trade on his profile, else any. The list is in his catalog's
     * order, so «any» is always the same row. Null for an empty list.
     */
    public static CatalogItem rowFor(List<CatalogItem> sameName, Trade workingIn, Set<Trade> profile) {
        CatalogItem visible = null;
        for (CatalogItem row : sameName) {
            if (workingIn != null && row.getTrade() == workingIn) {
                return row;
            }
            if (visible == null && isVisible(row, profile)) {
                visible = row;
            }
        }
        return visible != null ? visible : sameName.isEmpty() ? null : sameName.getFirst();
    }

    /**
     * The one trade an estimate is in: the trade every line that carries one shares. Lines of two
     * trades, or none, answer null.
     */
    public static Trade estimateTrade(Collection<EstimateItem> lines) {
        Set<Trade> trades = lines.stream().map(EstimateItem::getTrade).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        return trades.size() == 1 ? trades.iterator().next() : null;
    }

    /**
     * The trade of a line that names a position instead of picking a row (typed, imported,
     * dictated): the trades the master files that name under — his visible rows, else all of
     * them. One trade → that one. Several → the estimate's own trade when it is one of them
     * (owner ruling 2026-11-23); otherwise none, and the line sits outside any group, like any
     * typed line. Nothing is chosen on a hunch.
     */
    public static Trade typedLineTrade(Set<Trade> fileTrades, Trade estimateTrade) {
        if (fileTrades.size() == 1) {
            return fileTrades.iterator().next();
        }
        return estimateTrade != null && fileTrades.contains(estimateTrade) ? estimateTrade : null;
    }

    /**
     * {@code key → the trades the master files that name under}: his visible rows' trades when he
     * has any of that name, else every row's. The input to {@link #typedLineTrade}.
     */
    public static Map<String, Set<Trade>> fileTradesByKey(List<CatalogItem> catalog, Set<Trade> profile) {
        Map<String, List<CatalogItem>> byKey = new HashMap<>();
        for (CatalogItem c : catalog) {
            byKey.computeIfAbsent(key(c.getName(), c.getType(), c.getUnit()), k -> new ArrayList<>()).add(c);
        }
        Map<String, Set<Trade>> out = new HashMap<>(byKey.size());
        byKey.forEach((k, rows) -> {
            List<CatalogItem> visible = rows.stream().filter(r -> isVisible(r, profile)).toList();
            out.put(k, (visible.isEmpty() ? rows : visible).stream().map(CatalogItem::getTrade)
                    .collect(Collectors.toSet()));
        });
        return out;
    }

    /**
     * The catalog a name is MATCHED against (dictation, the bot): one row per name. Hidden rows
     * are left out; of several rows of one name the {@code context} trade's wins; rows that would
     * all answer alike (same price) collapse to the first. Rows of one name at DIFFERENT prices
     * with no context to choose stay — and {@code CatalogMatcher} refuses the tie, so the line
     * comes back unmatched instead of priced from the wrong trade.
     */
    public static List<CatalogItem> forMatching(List<CatalogItem> catalog, Set<Trade> profile, Trade context) {
        Map<String, List<CatalogItem>> byKey = new LinkedHashMap<>();
        for (CatalogItem c : catalog) {
            if (isVisible(c, profile)) {
                byKey.computeIfAbsent(key(c.getName(), c.getType(), c.getUnit()), k -> new ArrayList<>()).add(c);
            }
        }
        List<CatalogItem> out = new ArrayList<>(byKey.size());
        for (List<CatalogItem> rows : byKey.values()) {
            CatalogItem inContext = context == null ? null
                    : rows.stream().filter(r -> r.getTrade() == context).findFirst().orElse(null);
            if (inContext != null) {
                out.add(inContext);
            } else if (rows.stream().map(CatalogItem::getDefaultPrice).map(p -> p.stripTrailingZeros())
                    .distinct().count() == 1) {
                out.add(rows.getFirst());
            } else {
                out.addAll(rows);
            }
        }
        return out;
    }

    /** The catalog screen's rule (CatalogService): a library row of a trade off his profile is hidden. */
    static boolean isVisible(CatalogItem row, Set<Trade> profile) {
        return row.getSource() != CatalogItemSource.LIBRARY || profile.contains(row.getTrade());
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
