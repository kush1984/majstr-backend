package com.majstr.backend.service;

import com.majstr.backend.dto.MaterialParamsRequest;
import com.majstr.backend.dto.StoredMaterialParams;
import com.majstr.backend.entity.EstimateItem;
import com.majstr.backend.entity.EstimateMaterialParam;
import com.majstr.backend.entity.NormBasis;
import com.majstr.backend.repository.EstimateItemRepository;
import com.majstr.backend.repository.EstimateMaterialParamRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The three figures the material calculator has to ASK for, kept where the estimate is (V142).
 *
 * <p>{@code MaterialCalculatorService} still stores nothing: it derives every number it prints from
 * the estimate's own lines. What lives here is the other half — the perimeter, the розгортка and
 * the thickness, which are not derived from anything at all. They were kept in the browser's
 * {@code localStorage}, so the same estimate opened on a second device asked every question again
 * and answered it with our {@code default_param} instead of his figure.</p>
 *
 * <p>A write here is deliberately NOT a write to the estimate — see {@link EstimateMaterialParam}.
 * There is no {@code requireNotSigned}: a signed estimate is a document the client agreed to, and
 * what the master still has to buy in order to deliver it is not part of that document. He buys
 * most of it after the signature.</p>
 */
@Service
@RequiredArgsConstructor
public class MaterialParamService {

    private final EstimateMaterialParamRepository repository;
    private final EstimateItemRepository itemRepository;

    /** Everything answered on this estimate. Ownership is the caller's business — it loads first. */
    @Transactional(readOnly = true)
    public StoredMaterialParams load(UUID estimateId) {
        return read(repository.findByEstimateId(estimateId));
    }

    /**
     * Store one card's answer, leaving the other two questions exactly as they were.
     *
     * <p>Answers with the WHOLE resulting set, not with what it was handed: the request is a PATCH,
     * so what the screen has to know afterwards is the state of all three questions — and a cleared
     * field is a row that is gone, which an echo of the request could not express.</p>
     */
    @Transactional
    public StoredMaterialParams save(UUID estimateId, MaterialParamsRequest req) {
        Map<Key, EstimateMaterialParam> byKey = repository.findByEstimateId(estimateId).stream()
                .collect(Collectors.toMap(Key::of, p -> p, (a, b) -> a, HashMap::new));
        List<EstimateMaterialParam> save = new ArrayList<>();
        List<EstimateMaterialParam> drop = new ArrayList<>();

        if (req.perimeter() != null) {
            apply(byKey, save, drop, estimateId, NormBasis.PERIMETER, null, req.perimeter());
        }
        applyPerPosition(byKey, save, drop, estimateId, NormBasis.SECTION, req.sections());
        applyPerPosition(byKey, save, drop, estimateId, NormBasis.THICKNESS, req.thicknesses());

        if (!drop.isEmpty()) {
            repository.deleteAll(drop);
        }
        if (!save.isEmpty()) {
            repository.saveAll(save);
        }
        // byKey IS the result: it started as everything stored, gained each fresh row and lost every
        // cleared one — so the answer costs no second read.
        return read(new ArrayList<>(byKey.values()));
    }

    /**
     * Carry the answers into a duplicate (the crew copy, V85).
     *
     * <p>It is the same job on the same walls, so re-asking every question of a copy the master made
     * with one tap would be this table's own bug one level up. Consolidation is deliberately NOT
     * covered: a rollup merges several estimates and there is no honest answer to «whose perimeter»,
     * so the merged draft asks.</p>
     *
     * @param itemByOriginalId the copy's line id for each original line id, as {@code duplicate()}
     *                         already builds it once the copies are persisted
     */
    @Transactional
    public void copyToDuplicate(UUID sourceEstimateId, UUID copyEstimateId,
                                Map<UUID, UUID> itemByOriginalId) {
        List<EstimateMaterialParam> params = repository.findByEstimateId(sourceEstimateId);
        if (params.isEmpty()) {
            return;
        }
        List<EstimateMaterialParam> copies = new ArrayList<>(params.size());
        for (EstimateMaterialParam param : params) {
            UUID itemId = null;
            if (param.getEstimateItemId() != null) {
                itemId = itemByOriginalId.get(param.getEstimateItemId());
                if (itemId == null) {
                    continue; // the line did not make it into the copy — nothing to answer for
                }
            }
            copies.add(EstimateMaterialParam.builder()
                    .estimateId(copyEstimateId)
                    .estimateItemId(itemId)
                    .basis(param.getBasis())
                    .value(param.getValue())
                    .build());
        }
        repository.saveAll(copies);
    }

    // ---------------------------------------------------------------------------------------

    private void applyPerPosition(Map<Key, EstimateMaterialParam> byKey,
                                  List<EstimateMaterialParam> save,
                                  List<EstimateMaterialParam> drop,
                                  UUID estimateId, NormBasis basis,
                                  Map<UUID, BigDecimal> answers) {
        if (answers == null || answers.isEmpty()) {
            return;
        }
        // A key naming a line this estimate does not have is ignored: a position deleted between
        // the tap and the request is ordinary, and the foreign key would turn it into a failed save
        // of every other answer in the same batch.
        Set<UUID> known = itemRepository.findByEstimateIdOrderBySortOrderAscIdAsc(estimateId)
                .stream().map(EstimateItem::getId).collect(Collectors.toSet());
        for (Map.Entry<UUID, BigDecimal> entry : answers.entrySet()) {
            if (entry.getValue() == null || !known.contains(entry.getKey())) {
                continue;
            }
            apply(byKey, save, drop, estimateId, basis, entry.getKey(), entry.getValue());
        }
    }

    private void apply(Map<Key, EstimateMaterialParam> byKey,
                       List<EstimateMaterialParam> save, List<EstimateMaterialParam> drop,
                       UUID estimateId, NormBasis basis, UUID itemId, BigDecimal value) {
        Key key = new Key(itemId, basis);
        EstimateMaterialParam current = byKey.get(key);
        if (value.signum() <= 0) {
            // He cleared the field. «Not answered» has exactly one spelling here — the absence of a
            // row — and the CHECK would refuse a stored 0 anyway.
            if (current != null) {
                drop.add(current);
                byKey.remove(key);
            }
            return;
        }
        if (current != null) {
            current.setValue(value);
            save.add(current);
            return;
        }
        EstimateMaterialParam fresh = EstimateMaterialParam.builder()
                .estimateId(estimateId)
                .estimateItemId(itemId)
                .basis(basis)
                .value(value)
                .build();
        byKey.put(key, fresh);
        save.add(fresh);
    }

    private StoredMaterialParams read(List<EstimateMaterialParam> params) {
        if (params.isEmpty()) {
            return StoredMaterialParams.EMPTY;
        }
        BigDecimal perimeter = null;
        Map<UUID, BigDecimal> sections = new LinkedHashMap<>();
        Map<UUID, BigDecimal> thicknesses = new LinkedHashMap<>();
        for (EstimateMaterialParam param : params) {
            switch (param.getBasis()) {
                case PERIMETER -> perimeter = param.getValue();
                case SECTION -> sections.put(param.getEstimateItemId(), param.getValue());
                case THICKNESS -> thicknesses.put(param.getEstimateItemId(), param.getValue());
                default -> { /* QUANTITY is read off the line and is never asked for */ }
            }
        }
        return new StoredMaterialParams(perimeter, sections, thicknesses);
    }

    /** (position, question) — the pair the two partial unique indexes enforce. */
    private record Key(UUID itemId, NormBasis basis) {
        static Key of(EstimateMaterialParam param) {
            return new Key(param.getEstimateItemId(), param.getBasis());
        }
    }
}
