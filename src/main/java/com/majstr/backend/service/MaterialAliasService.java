package com.majstr.backend.service;

import com.majstr.backend.dto.MaterialAliasRequest;
import com.majstr.backend.dto.NormPosition;
import com.majstr.backend.entity.MaterialNormAlias;
import com.majstr.backend.entity.Trade;
import com.majstr.backend.entity.Unit;
import com.majstr.backend.exception.MaterialNormValidationException;
import com.majstr.backend.repository.MaterialNormAliasRepository;
import com.majstr.backend.repository.MaterialNormRepository;
import com.majstr.backend.service.importer.CatalogMatcher;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * «Рахувати як» (V204): a master's own wording of a position, counted as a library position. The library
 * positions are read from the norms themselves, so only a position that buys something is ever offered.
 * Repositories ONLY — {@link MaterialCalculatorService} calls in, and must not be called back.
 */
@Service
@RequiredArgsConstructor
public class MaterialAliasService {

    /**
     * «Це те саме, що …?» is asked only of a CLOSE library name — the same job in other words. One shared
     * stem is not that («штукатурка стін» for «Фарбування стін за 2 рази»): a line with nothing this close
     * is his own work, and the screen says plainly that its materials are not counted.
     */
    static final double MIN_SUGGESTION = 0.5;
    /** A position of the line's own trade wins a tie — it never lifts a weak name over the bar. */
    private static final double SAME_TRADE_BONUS = 0.1;
    /** One question, one answer: the closest position only. */
    static final int SUGGESTIONS = 1;

    private final MaterialNormAliasRepository aliasRepository;
    private final MaterialNormRepository normRepository;

    @Transactional(readOnly = true)
    public List<NormPosition> libraryPositions() {
        return normRepository.findLibraryPositions().stream()
                .map(p -> new NormPosition(p.getNameKey(), p.getName(), Unit.valueOf(p.getUnit()),
                        p.getTrade() == null ? null : Trade.valueOf(p.getTrade())))
                .toList();
    }

    /** The closest library positions to a line's name, same unit, best first — offered, never applied. */
    public List<NormPosition> suggestions(String name, Unit unit, Trade trade, List<NormPosition> library) {
        return library.stream()
                .filter(p -> p.unit() == unit)
                .map(p -> Map.entry(p, CatalogMatcher.similarity(name, p.name())))
                .filter(e -> e.getValue() >= MIN_SUGGESTION)
                .map(e -> Map.entry(e.getKey(), e.getValue()
                        + (trade != null && trade != Trade.OTHER && e.getKey().trade() == trade ? SAME_TRADE_BONUS : 0)))
                .sorted(Map.Entry.<NormPosition, Double>comparingByValue().reversed())
                .limit(SUGGESTIONS)
                .map(Map.Entry::getKey)
                .toList();
    }

    /**
     * The one library position a wording names once SPELLING is set aside — ґ/г, a Latin x or × for the
     * Cyrillic х of a format, apostrophes, quotes and punctuation. Not a guess: the library must have exactly
     * one position of that unit under the same letters, or nothing is returned. «Ґрунтування» finds
     * «грунтування»; «Шпаклівка стін» finds nothing, because two positions start that way.
     */
    public static Map<String, String> spellingIndex(List<NormPosition> library) {
        Map<String, String> index = new HashMap<>();
        Map<String, Integer> seen = new HashMap<>();
        for (NormPosition p : library) {
            String k = key(spelling(p.key()), p.unit());
            seen.merge(k, 1, Integer::sum);
            index.put(k, p.key());
        }
        seen.forEach((k, n) -> {
            if (n > 1) {
                index.remove(k);
            }
        });
        return index;
    }

    /** A name with its spelling variants folded away — see {@link #spellingIndex}. */
    public static String spelling(String name) {
        String s = NameKeys.of(name).replace('ґ', 'г').replaceAll("['’ʼ`]", "");
        s = s.replaceAll("(\\d)\\s*[xX×*хХ]\\s*(\\d)", "$1х$2");
        s = s.replaceAll("[^\\p{L}\\p{N}]+", " ");
        return s.trim();
    }

    /** The owner's answers, by «wording key|unit». */
    @Transactional(readOnly = true)
    public Map<String, MaterialNormAlias> aliases(UUID ownerId) {
        Map<String, MaterialNormAlias> byKey = new HashMap<>();
        if (ownerId != null) {
            aliasRepository.findByOwnerId(ownerId).forEach(a -> byKey.put(key(a.getNameKey(), a.getUnit()), a));
        }
        return byKey;
    }

    static String key(String nameKey, Unit unit) {
        return nameKey + "|" + unit;
    }

    @Transactional
    public void save(UUID ownerId, MaterialAliasRequest req) {
        String target = req.asNameKey() == null || req.asNameKey().isBlank() ? null : req.asNameKey().trim();
        if (target != null && libraryPositions().stream()
                .noneMatch(p -> p.key().equals(target) && p.unit() == req.unit())) {
            throw new MaterialNormValidationException("error.material-alias.unknown");
        }
        String nameKey = NameKeys.of(req.name());
        aliasRepository.lockOwner(ownerId);
        MaterialNormAlias row = aliasRepository.findByOwnerIdAndNameKeyAndUnit(ownerId, nameKey, req.unit())
                .orElseGet(() -> MaterialNormAlias.builder().ownerId(ownerId).nameKey(nameKey).unit(req.unit()).build());
        row.setAsNameKey(target);
        aliasRepository.save(row);
    }

    /** Forget the answer — the line is asked about again. Idempotent. */
    @Transactional
    public void delete(UUID ownerId, String name, Unit unit) {
        aliasRepository.deleteOne(ownerId, NameKeys.of(name), unit);
    }
}
