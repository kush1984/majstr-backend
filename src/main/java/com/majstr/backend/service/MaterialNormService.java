package com.majstr.backend.service;

import com.majstr.backend.dto.MaterialNormResponse;
import com.majstr.backend.entity.MaterialNorm;
import com.majstr.backend.exception.MaterialNormValidationException;
import com.majstr.backend.exception.ResourceNotFoundException;
import com.majstr.backend.repository.MaterialNormRepository;
import com.majstr.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * «Моя норма, назавжди» — a master's own consumption coefficient.
 *
 * <p>A shipped norm FORKS ON WRITE, the pattern {@code TemplateDefaultOverride} established (V113):
 * the first correction copies the row under his {@code owner_id} and the copy hides the original for
 * him from then on. Editing the shared row would change every other master's arithmetic, and a
 * per-master delta table beside the norms would need the whole lookup — the key AND the trade
 * filter — built a second time.</p>
 *
 * <p>Two consequences the caller must respect:</p>
 * <ol>
 *   <li><b>The id in the answer may differ from the id in the URL</b>, because the write landed on
 *       the fork. Key anything cached under the id that comes back.</li>
 *   <li><b>Restoring is a DELETE of the fork</b>, not a copy of the shipped figure onto it: the
 *       default must keep tracking whatever a later catalog round revises it to.</li>
 * </ol>
 *
 * <p>Both entry points are idempotent under a stale screen. A client still holding the default's id
 * after the fork exists is ordinary — he calculated before he edited — so a write looks the fork up
 * by the natural key instead of creating a second one against the unique index.</p>
 */
@Service
@RequiredArgsConstructor
public class MaterialNormService {

    private final MaterialNormRepository normRepository;
    private final UserRepository userRepository;

    /** Store the master's own coefficient, forking the shipped norm the first time. */
    @Transactional
    public MaterialNormResponse saveOwn(UUID normId, UUID ownerId, BigDecimal qtyPerUnit) {
        MaterialNorm norm = load(normId, ownerId);
        if (norm.getMaterial() == null) {
            // «Checked, consumes nothing» (V127) is a verdict, not a quantity: the row carries no
            // coefficient, and inventing one would put a material in his list.
            throw new MaterialNormValidationException("error.material-norm.no-material");
        }
        MaterialNorm target = norm.getOwner() != null ? norm : fork(norm, ownerId);
        target.setQtyPerUnit(qtyPerUnit);
        return response(normRepository.save(target));
    }

    /** Drop the master's own coefficient and fall back to the shipped one. Idempotent. */
    @Transactional
    public void restoreDefault(UUID normId, UUID ownerId) {
        MaterialNorm norm = load(normId, ownerId);
        if (norm.getOwner() != null) {
            normRepository.delete(norm);
            return;
        }
        own(norm, ownerId).ifPresent(normRepository::delete);
    }

    // ---------------------------------------------------------------------------------------

    /** Someone else's norm answers 404, not 403: he has no way to know it exists. */
    private MaterialNorm load(UUID normId, UUID ownerId) {
        MaterialNorm norm = normRepository.findById(normId)
                .orElseThrow(() -> new ResourceNotFoundException("Material norm not found"));
        if (norm.getOwner() != null && !norm.getOwner().getId().equals(ownerId)) {
            throw new ResourceNotFoundException("Material norm not found");
        }
        return norm;
    }

    private MaterialNorm fork(MaterialNorm base, UUID ownerId) {
        return own(base, ownerId).orElseGet(() -> MaterialNorm.builder()
                .owner(userRepository.getReferenceById(ownerId))
                .trade(base.getTrade())
                .nameKey(base.getNameKey())
                .unit(base.getUnit())
                .material(base.getMaterial())
                .qtyPerUnit(base.getQtyPerUnit())
                .basis(base.getBasis())
                // The question the norm asks travels with it: a THICKNESS fork that lost its
                // suggestion would ask for millimetres over an empty field (V137).
                .defaultParam(base.getDefaultParam())
                // And so does the joint the coefficient was written for (V144): the fork is never
                // rescaled today, so nothing reads it yet — but a copy that dropped it would be a
                // number with no record of what it assumed.
                .baselineParam(base.getBaselineParam())
                .wastePercent(base.getWastePercent())
                .sortOrder(base.getSortOrder())
                .build());
    }

    /**
     * The master's own row for the same norm, matched on the natural key {@code ux_material_norm}
     * enforces. The trade and the material are filtered here rather than in the query because both
     * are nullable, and «either both null or equal» reads far worse as JPQL.
     *
     * <p><b>The trade is matched first and then given up on</b> (review B-107). A migration that
     * re-files a shipped position to {@code trade = NULL} is supposed to carry the forks with it,
     * and V143 repaired the ones V137 left behind — but the matching is what has to survive the next
     * one. Without the fallback, a fork still sitting under the old trade is INVISIBLE here: the
     * save writes a SECOND fork, the unique index lets it in (the trade differs), and from then on
     * two owned rows answer for one norm and the master cannot see either of them from the other.
     * One stranded row that keeps being found is a bad state we can repair; two are a bad state the
     * read path has to guess between.</p>
     */
    private Optional<MaterialNorm> own(MaterialNorm base, UUID ownerId) {
        UUID materialId = materialId(base);
        List<MaterialNorm> mine = normRepository
                .findByOwnerIdAndNameKeyAndUnit(ownerId, base.getNameKey(), base.getUnit()).stream()
                .filter(n -> Objects.equals(materialId, materialId(n)))
                .toList();
        return mine.stream()
                .filter(n -> n.getTrade() == base.getTrade())
                .findFirst()
                .or(() -> mine.stream().findFirst());
    }

    private UUID materialId(MaterialNorm norm) {
        return norm.getMaterial() == null ? null : norm.getMaterial().getId();
    }

    private MaterialNormResponse response(MaterialNorm norm) {
        return new MaterialNormResponse(norm.getId(), materialId(norm),
                norm.getQtyPerUnit(), norm.getOwner() != null);
    }
}
