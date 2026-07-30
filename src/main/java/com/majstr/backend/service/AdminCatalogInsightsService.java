package com.majstr.backend.service;

import com.majstr.backend.dto.CatalogInsights;
import com.majstr.backend.entity.CatalogInsightDismissal;
import com.majstr.backend.entity.CatalogInsightKind;
import com.majstr.backend.entity.CatalogTemplate;
import com.majstr.backend.entity.ItemType;
import com.majstr.backend.entity.Unit;
import com.majstr.backend.repository.CatalogInsightDismissalRepository;
import com.majstr.backend.repository.CatalogItemRepository;
import com.majstr.backend.repository.CatalogTemplateRepository;
import com.majstr.backend.repository.EstimateItemRepository;
import com.majstr.backend.service.catalog.CatalogNameKey;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * What masters do with the catalog, read back as a lesson about our defaults.
 *
 * <p><b>This is not a moderation queue.</b> The default catalog was assembled quickly and nobody
 * knows how much of it is useful. These three lists are the instrument for finding out — the
 * point is to learn what a good position looks like, not to approve submissions.</p>
 *
 * <ol>
 *   <li><b>Gaps</b> — positions masters invented that we do not cover. Ranked by how many
 *       masters independently arrived at the same thing, which is a much stronger signal than
 *       recency.</li>
 *   <li><b>Reworded</b> — positions we DO cover that masters typed differently anyway. The most
 *       actionable of the three: they could not find ours, so ours is written wrong. The fix is
 *       to rename our default, not to add theirs.
 *       <p><b>Only spelling-level differences are detected</b> — punctuation, connectors, word
 *       order. A genuine SYNONYM («Штукатурка стін» for «Оштукатурювання поверхонь стін
 *       цементно-піщаним розчином») shares one word and no normalisation can equate the two;
 *       it appears in the gap list instead, where a human recognises it. That is the right way
 *       to be wrong: a candidate someone reviews, rather than a silent merge of two positions
 *       that only looked alike. If this ever needs automating, it is an LLM pass over the gap
 *       list ("does the default catalog already contain this work under another name?"), not a
 *       cleverer string rule.</p></li>
 *   <li><b>Unused</b> — our defaults that no estimate has ever resembled. Dead weight, and the
 *       only list that answers "is this catalog any use to anyone".</li>
 * </ol>
 *
 * <p><b>Prices are read, never republished.</b> Aggregates across accounts only. A master's own
 * pricing is their commercial data, and handing it to competitors is not something they agreed
 * to by using the app.</p>
 *
 * <p>All three are computed in memory over aggregated rows: a few thousand distinct names, run
 * on demand from one admin screen. If the catalog ever grows enough for that to hurt, cache the
 * result — do not move {@link CatalogNameKey} into SQL, or the rule exists twice.</p>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class AdminCatalogInsightsService {

    private final CatalogItemRepository catalogItemRepository;
    private final CatalogTemplateRepository templateRepository;
    private final EstimateItemRepository estimateItemRepository;
    private final CatalogInsightDismissalRepository dismissalRepository;

    /** Positions masters created that our defaults do not cover, most-corroborated first. */
    @Transactional(readOnly = true)
    public List<CatalogInsights.NewPosition> newPositions() {
        Map<String, CatalogTemplate> ours = defaultsByKey();
        Set<String> dismissed = dismissedKeys(CatalogInsightKind.NEW_POSITION);

        return catalogItemRepository.aggregateMasterPositions().stream()
                .map(row -> Map.entry(CatalogNameKey.of(row.getName()), row))
                .filter(e -> !e.getKey().isEmpty())
                .filter(e -> !ours.containsKey(e.getKey()))
                .filter(e -> !dismissed.contains(e.getKey()))
                // Several exact spellings can share one key — fold them, keeping the most
                // corroborated spelling as the one shown.
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue,
                        (a, b) -> a.getMasters() >= b.getMasters() ? a : b, LinkedHashMap::new))
                .entrySet().stream()
                .map(e -> new CatalogInsights.NewPosition(
                        e.getKey(), e.getValue().getName(),
                        ItemType.valueOf(e.getValue().getType()), Unit.valueOf(e.getValue().getUnit()),
                        e.getValue().getCategory(), e.getValue().getMasters(), e.getValue().getFirstSeen(),
                        e.getValue().getMedianPrice(), e.getValue().getMinPrice(), e.getValue().getMaxPrice()))
                .sorted(Comparator.comparingLong(CatalogInsights.NewPosition::masters).reversed()
                        .thenComparing(CatalogInsights.NewPosition::name))
                .toList();
    }

    /**
     * Positions we already ship, that masters wrote differently. Our name and theirs, side by
     * side — the gap between them IS the finding.
     */
    @Transactional(readOnly = true)
    public List<CatalogInsights.RenamedPosition> rewordedPositions() {
        Map<String, CatalogTemplate> ours = defaultsByKey();
        Set<String> dismissed = dismissedKeys(CatalogInsightKind.RENAMED_POSITION);

        return catalogItemRepository.aggregateMasterPositions().stream()
                .map(row -> Map.entry(CatalogNameKey.of(row.getName()), row))
                .filter(e -> ours.containsKey(e.getKey()))
                .filter(e -> !dismissed.contains(e.getKey()))
                // Same key AND same text is just a seeded copy — uninteresting. Only a different
                // spelling says anything about our wording.
                .filter(e -> !ours.get(e.getKey()).getName().equalsIgnoreCase(e.getValue().getName()))
                .map(e -> {
                    CatalogTemplate our = ours.get(e.getKey());
                    return new CatalogInsights.RenamedPosition(
                            e.getKey(), our.getName(), our.getId(), e.getValue().getName(),
                            ItemType.valueOf(e.getValue().getType()), Unit.valueOf(e.getValue().getUnit()),
                            e.getValue().getMasters(), e.getValue().getFirstSeen());
                })
                .sorted(Comparator.comparingLong(CatalogInsights.RenamedPosition::masters).reversed()
                        .thenComparing(CatalogInsights.RenamedPosition::ourName))
                .toList();
    }

    /**
     * Our defaults that nothing in any estimate resembles.
     *
     * <p>Approximate by construction — estimate lines are snapshots with no link back to the
     * catalog (that is what keeps a signed estimate's figures intact), so the only join
     * available is the name. Reliable for "nobody ever wrote anything like this"; not a usage
     * statistic, and it must not be presented as one.
     */
    @Transactional(readOnly = true)
    public List<CatalogInsights.UnusedDefault> unusedDefaults() {
        Set<String> usedKeys = estimateItemRepository.aggregateUsedNames().stream()
                .map(r -> CatalogNameKey.of(r.getName()))
                .filter(k -> !k.isEmpty())
                .collect(Collectors.toSet());
        Set<String> dismissed = dismissedKeys(CatalogInsightKind.UNUSED_DEFAULT);

        return templateRepository.findAll().stream()
                .map(t -> Map.entry(CatalogNameKey.of(t.getName()), t))
                .filter(e -> !e.getKey().isEmpty())
                .filter(e -> !usedKeys.contains(e.getKey()))
                .filter(e -> !dismissed.contains(e.getKey()))
                .map(e -> new CatalogInsights.UnusedDefault(
                        e.getKey(), e.getValue().getId(), e.getValue().getName(),
                        e.getValue().getTrade(), e.getValue().getCategory(),
                        e.getValue().getType(), e.getValue().getUnit(),
                        e.getValue().getSuggestedPrice(), true))
                .sorted(Comparator.comparing((CatalogInsights.UnusedDefault u) -> u.trade().name())
                        .thenComparing(CatalogInsights.UnusedDefault::name))
                .toList();
    }

    /**
     * Stop showing this candidate. Keyed by the normalised name, so one decision covers every
     * spelling of the same work rather than returning once per master who typed it.
     */
    @Transactional
    public void dismiss(CatalogInsightKind kind, String nameKey, String sampleName, UUID actor, String note) {
        dismissalRepository.findByKindAndNameKey(kind, nameKey).ifPresentOrElse(
                existing -> {
                    existing.setDismissedBy(actor);
                    existing.setNote(note);
                },
                () -> dismissalRepository.save(CatalogInsightDismissal.builder()
                        .kind(kind).nameKey(nameKey).sampleName(sampleName)
                        .dismissedBy(actor).note(note).build()));
        log.info("admin {} dismissed {} candidate '{}'", actor, kind, sampleName);
    }

    /** Bring a dismissed candidate back — a judgement call can be wrong. */
    @Transactional
    public void undismiss(CatalogInsightKind kind, String nameKey) {
        dismissalRepository.findByKindAndNameKey(kind, nameKey).ifPresent(dismissalRepository::delete);
    }

    /** Our default catalog indexed by comparison key. Later duplicates lose — V71 removed them. */
    private Map<String, CatalogTemplate> defaultsByKey() {
        Map<String, CatalogTemplate> byKey = new HashMap<>();
        for (CatalogTemplate t : templateRepository.findAll()) {
            String key = CatalogNameKey.of(t.getName());
            if (!key.isEmpty()) {
                byKey.putIfAbsent(key, t);
            }
        }
        return byKey;
    }

    private Set<String> dismissedKeys(CatalogInsightKind kind) {
        return dismissalRepository.findByKind(kind).stream()
                .map(CatalogInsightDismissal::getNameKey)
                .collect(Collectors.toSet());
    }
}
