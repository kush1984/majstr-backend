package com.majstr.backend.service;

import com.majstr.backend.dto.MaterialNormResponse;
import com.majstr.backend.entity.Material;
import com.majstr.backend.entity.MaterialNorm;
import com.majstr.backend.entity.NormBasis;
import com.majstr.backend.entity.Trade;
import com.majstr.backend.entity.Unit;
import com.majstr.backend.entity.User;
import com.majstr.backend.exception.MaterialNormValidationException;
import com.majstr.backend.exception.ResourceNotFoundException;
import com.majstr.backend.repository.MaterialNormRepository;
import com.majstr.backend.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * «Моя норма, назавжди» — the fork-on-write half of the material calculator.
 *
 * <p>Every test here is about one of the two ways this breaks quietly: writing onto the SHARED row
 * (which would move every other master's arithmetic), and creating a SECOND fork for a natural key
 * that already has one (which the unique index turns into a 500 on an ordinary stale screen).</p>
 */
@ExtendWith(MockitoExtension.class)
class MaterialNormServiceTest {

    private static final UUID OWNER = UUID.randomUUID();
    private static final String NAME_KEY = "монтаж на стіни";

    @Mock MaterialNormRepository normRepository;
    @Mock UserRepository userRepository;

    @InjectMocks MaterialNormService service;

    private final Material sheet = Material.builder()
            .id(UUID.randomUUID()).code("GKL_SHEET").name("Лист ГКЛ").unit(Unit.M2).build();

    @Test
    void correctingAShippedNormForksItAndLeavesTheSharedRowAlone() {
        MaterialNorm shipped = norm(null, "1.0");
        when(normRepository.findById(shipped.getId())).thenReturn(Optional.of(shipped));
        when(normRepository.findByOwnerIdAndNameKeyAndUnit(OWNER, NAME_KEY, Unit.M2))
                .thenReturn(List.of());
        when(userRepository.getReferenceById(OWNER)).thenReturn(user());
        when(normRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        MaterialNormResponse response = service.saveOwn(shipped.getId(), OWNER, new BigDecimal("1.2"));

        ArgumentCaptor<MaterialNorm> saved = ArgumentCaptor.captor();
        verify(normRepository).save(saved.capture());
        assertThat(saved.getValue()).isNotSameAs(shipped);
        assertThat(saved.getValue().getOwner().getId()).isEqualTo(OWNER);
        assertThat(saved.getValue().getQtyPerUnit()).isEqualByComparingTo("1.2");
        // The whole point: the shipped figure every other master reads is untouched.
        assertThat(shipped.getQtyPerUnit()).isEqualByComparingTo("1.0");
        assertThat(response.ownNorm()).isTrue();
        assertThat(response.materialId()).isEqualTo(sheet.getId());
    }

    /** The fork carries the rest of the norm verbatim — it is the same rule, a different number. */
    @Test
    void theForkKeepsEverythingAboutTheNormExceptTheCoefficient() {
        MaterialNorm shipped = norm(null, "1.0");
        shipped.setBasis(NormBasis.PERIMETER);
        shipped.setWastePercent(new BigDecimal("5.00"));
        shipped.setSortOrder(7);
        when(normRepository.findById(shipped.getId())).thenReturn(Optional.of(shipped));
        when(normRepository.findByOwnerIdAndNameKeyAndUnit(OWNER, NAME_KEY, Unit.M2))
                .thenReturn(List.of());
        when(userRepository.getReferenceById(OWNER)).thenReturn(user());
        when(normRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        service.saveOwn(shipped.getId(), OWNER, new BigDecimal("2"));

        ArgumentCaptor<MaterialNorm> saved = ArgumentCaptor.captor();
        verify(normRepository).save(saved.capture());
        assertThat(saved.getValue().getBasis()).isEqualTo(NormBasis.PERIMETER);
        assertThat(saved.getValue().getWastePercent()).isEqualByComparingTo("5.00");
        assertThat(saved.getValue().getSortOrder()).isEqualTo(7);
        assertThat(saved.getValue().getTrade()).isEqualTo(Trade.DRYWALL);
    }

    @Test
    void correctingHisOwnNormWritesOnThatRowInsteadOfForkingAgain() {
        MaterialNorm mine = norm(user(), "1.2");
        when(normRepository.findById(mine.getId())).thenReturn(Optional.of(mine));
        when(normRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        MaterialNormResponse response = service.saveOwn(mine.getId(), OWNER, new BigDecimal("1.4"));

        assertThat(response.id()).isEqualTo(mine.getId());
        assertThat(mine.getQtyPerUnit()).isEqualByComparingTo("1.4");
        verify(normRepository, never()).findByOwnerIdAndNameKeyAndUnit(any(), any(), any());
    }

    /**
     * He calculated before he edited, so his screen still carries the DEFAULT's id — perfectly
     * ordinary. Forking blindly here would hit {@code ux_material_norm} and 500 on him.
     */
    @Test
    void aStaleScreenHoldingTheDefaultIdWritesOnTheForkThatAlreadyExists() {
        MaterialNorm shipped = norm(null, "1.0");
        MaterialNorm mine = norm(user(), "1.2");
        when(normRepository.findById(shipped.getId())).thenReturn(Optional.of(shipped));
        when(normRepository.findByOwnerIdAndNameKeyAndUnit(OWNER, NAME_KEY, Unit.M2))
                .thenReturn(List.of(mine));
        when(normRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        MaterialNormResponse response = service.saveOwn(shipped.getId(), OWNER, new BigDecimal("1.5"));

        assertThat(response.id()).isEqualTo(mine.getId());
        assertThat(mine.getQtyPerUnit()).isEqualByComparingTo("1.5");
        verify(userRepository, never()).getReferenceById(any());
    }

    /** Another material under the same name and unit is a different norm, not the same one. */
    @Test
    void aForkForAnotherMaterialIsNotMistakenForThisOne() {
        MaterialNorm shipped = norm(null, "1.0");
        MaterialNorm otherMaterial = norm(user(), "20");
        otherMaterial.setMaterial(Material.builder()
                .id(UUID.randomUUID()).code("SCREW").name("Саморізи").unit(Unit.PIECE).build());
        when(normRepository.findById(shipped.getId())).thenReturn(Optional.of(shipped));
        when(normRepository.findByOwnerIdAndNameKeyAndUnit(OWNER, NAME_KEY, Unit.M2))
                .thenReturn(List.of(otherMaterial));
        when(userRepository.getReferenceById(OWNER)).thenReturn(user());
        when(normRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        service.saveOwn(shipped.getId(), OWNER, new BigDecimal("1.2"));

        ArgumentCaptor<MaterialNorm> saved = ArgumentCaptor.captor();
        verify(normRepository).save(saved.capture());
        assertThat(saved.getValue()).isNotSameAs(otherMaterial);
        assertThat(saved.getValue().getMaterial()).isEqualTo(sheet);
    }

    @Test
    void aWorkThatConsumesNothingHasNoCoefficientToCorrect() {
        MaterialNorm nothing = norm(null, null);
        nothing.setMaterial(null);
        when(normRepository.findById(nothing.getId())).thenReturn(Optional.of(nothing));

        assertThatThrownBy(() -> service.saveOwn(nothing.getId(), OWNER, new BigDecimal("1.2")))
                .isInstanceOf(MaterialNormValidationException.class);
        verify(normRepository, never()).save(any());
    }

    /** 404, not 403: he has no way to know another master's norm exists. */
    @Test
    void anotherMastersNormIsNotFound() {
        MaterialNorm theirs = norm(User.builder().id(UUID.randomUUID()).build(), "1.2");
        when(normRepository.findById(theirs.getId())).thenReturn(Optional.of(theirs));

        assertThatThrownBy(() -> service.saveOwn(theirs.getId(), OWNER, new BigDecimal("1.4")))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void restoringDeletesHisForkSoTheDefaultTracksLaterRevisionsAgain() {
        MaterialNorm mine = norm(user(), "1.2");
        when(normRepository.findById(mine.getId())).thenReturn(Optional.of(mine));

        service.restoreDefault(mine.getId(), OWNER);

        verify(normRepository).delete(mine);
    }

    @Test
    void restoringThroughTheDefaultsIdFindsTheForkAndIsANoOpWithoutOne() {
        MaterialNorm shipped = norm(null, "1.0");
        MaterialNorm mine = norm(user(), "1.2");
        when(normRepository.findById(shipped.getId())).thenReturn(Optional.of(shipped));
        when(normRepository.findByOwnerIdAndNameKeyAndUnit(OWNER, NAME_KEY, Unit.M2))
                .thenReturn(List.of(mine), List.of());

        service.restoreDefault(shipped.getId(), OWNER);
        service.restoreDefault(shipped.getId(), OWNER);

        verify(normRepository).delete(mine);
        verify(normRepository, never()).delete(shipped);
    }

    // ---------------------------------------------------------------------------------------

    private User user() {
        return User.builder().id(OWNER).build();
    }

    private MaterialNorm norm(User owner, String qty) {
        return MaterialNorm.builder()
                .id(UUID.randomUUID())
                .owner(owner)
                .trade(Trade.DRYWALL)
                .nameKey(NAME_KEY)
                .unit(Unit.M2)
                .material(sheet)
                .qtyPerUnit(qty == null ? null : new BigDecimal(qty))
                .basis(NormBasis.QUANTITY)
                .wastePercent(BigDecimal.ZERO)
                .build();
    }
}
