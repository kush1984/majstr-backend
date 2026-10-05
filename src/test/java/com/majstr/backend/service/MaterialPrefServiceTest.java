package com.majstr.backend.service;

import com.majstr.backend.dto.MaterialPrefsRequest;
import com.majstr.backend.entity.MasterMaterialPref;
import com.majstr.backend.entity.MaterialPrefKey;
import com.majstr.backend.exception.MaterialPrefValidationException;
import com.majstr.backend.repository.MasterMaterialPrefRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * A habit is stored in a form the calculator can actually read, and inside a band a real answer
 * falls in (review B-19).
 */
@ExtendWith(MockitoExtension.class)
class MaterialPrefServiceTest {

    @Mock MasterMaterialPrefRepository prefRepository;
    @InjectMocks MaterialPrefService service;

    private final UUID userId = UUID.randomUUID();

    @Test
    void aCommaDecimalIsStoredAsTheCalculatorReadsIt() {
        // «2,5» from a Ukrainian keyboard was stored verbatim, the screen said «збережено», and the
        // READ path's BigDecimal then threw on the comma and treated it as «no answer» — a saved
        // correction that changed nothing, with nothing on the screen to say so.
        given(prefRepository.findByUserIdAndPrefKey(userId, MaterialPrefKey.TILE_JOINT_MM))
                .willReturn(Optional.empty());
        given(prefRepository.findByUserId(userId)).willReturn(List.of());

        service.save(userId, new MaterialPrefsRequest(Map.of("TILE_JOINT_MM", "2,5")));

        ArgumentCaptor<MasterMaterialPref> saved = ArgumentCaptor.forClass(MasterMaterialPref.class);
        verify(prefRepository).save(saved.capture());
        assertThat(saved.getValue().getPrefValue()).isEqualTo("2.5");
    }

    @Test
    void anOutOfBandPaintCoverageIsRefused() {
        // PAINT_COVERAGE is m² per litre and DIVIDES: 0,5 multiplies every paint figure by eighteen
        // and sends him for 54 litres for one room. Nothing bounded it.
        given(prefRepository.findByUserIdAndPrefKey(userId, MaterialPrefKey.PAINT_COVERAGE))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> service.save(userId,
                new MaterialPrefsRequest(Map.of("PAINT_COVERAGE", "0.5"))))
                .isInstanceOf(MaterialPrefValidationException.class)
                .hasMessage("error.material-pref.invalid");
        verify(prefRepository, never()).save(any(MasterMaterialPref.class));
    }

    @Test
    void aFractionalCoatCountIsRefused_butTheBandEdgeIsFine() {
        given(prefRepository.findByUserIdAndPrefKey(userId, MaterialPrefKey.PAINT_COATS))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> service.save(userId,
                new MaterialPrefsRequest(Map.of("PAINT_COATS", "2.5"))))
                .isInstanceOf(MaterialPrefValidationException.class);

        given(prefRepository.findByUserId(userId)).willReturn(List.of());
        service.save(userId, new MaterialPrefsRequest(Map.of("PAINT_COATS", "4")));
        ArgumentCaptor<MasterMaterialPref> saved = ArgumentCaptor.forClass(MasterMaterialPref.class);
        verify(prefRepository).save(saved.capture());
        assertThat(saved.getValue().getPrefValue()).isEqualTo("4");
    }

    @Test
    void aSheetFormatMustLookLikeOne_andIsNormalised() {
        given(prefRepository.findByUserIdAndPrefKey(userId, MaterialPrefKey.GKL_SHEET))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> service.save(userId,
                new MaterialPrefsRequest(Map.of("GKL_SHEET", "великий"))))
                .isInstanceOf(MaterialPrefValidationException.class);

        given(prefRepository.findByUserId(userId)).willReturn(List.of());
        service.save(userId, new MaterialPrefsRequest(Map.of("GKL_SHEET", "1200 × 2500")));
        ArgumentCaptor<MasterMaterialPref> saved = ArgumentCaptor.forClass(MasterMaterialPref.class);
        verify(prefRepository).save(saved.capture());
        assertThat(saved.getValue().getPrefValue()).isEqualTo("1200x2500");
    }

    @Test
    void zeroWasteIsAnAnswer_notAnError() {
        // «я не беру запас» is a habit like any other — 0 must survive the band check.
        given(prefRepository.findByUserIdAndPrefKey(userId, MaterialPrefKey.WASTE_PERCENT))
                .willReturn(Optional.empty());
        given(prefRepository.findByUserId(userId)).willReturn(List.of());

        service.save(userId, new MaterialPrefsRequest(Map.of("WASTE_PERCENT", "0")));

        ArgumentCaptor<MasterMaterialPref> saved = ArgumentCaptor.forClass(MasterMaterialPref.class);
        verify(prefRepository).save(saved.capture());
        assertThat(saved.getValue().getPrefValue()).isEqualTo("0");
    }

    @Test
    void aBlankValueStillForgetsTheHabit() {
        MasterMaterialPref existing = MasterMaterialPref.builder()
                .userId(userId).prefKey(MaterialPrefKey.WASTE_PERCENT).prefValue("10").build();
        given(prefRepository.findByUserIdAndPrefKey(userId, MaterialPrefKey.WASTE_PERCENT))
                .willReturn(Optional.of(existing));
        given(prefRepository.findByUserId(userId)).willReturn(List.of());

        service.save(userId, new MaterialPrefsRequest(Map.of("WASTE_PERCENT", "  ")));

        verify(prefRepository).delete(existing);
        verify(prefRepository, never()).save(any(MasterMaterialPref.class));
    }
}
