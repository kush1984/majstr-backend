package com.majstr.backend.service.catalog;

import com.majstr.backend.entity.Unit;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CatalogRenamesTest {

    private final CatalogRenames.Lookup lookup = new CatalogRenames.Lookup(Map.of(
            "монтаж маурлєта|LINEAR_METER", "монтаж маурлата",
            "стара назва|M2", "проміжна назва",
            "проміжна назва|M2", "нова назва",
            "петля a|M2", "петля b",
            "петля b|M2", "петля a"));

    @Test
    void anOldWording_becomesTheNewOne_bySpellingUntidinessToo() {
        assertThat(lookup.current("  Монтаж   маурлєта ", Unit.LINEAR_METER)).isEqualTo("монтаж маурлата");
        assertThat(lookup.renamed("Монтаж маурлєта", "LINEAR_METER")).isTrue();
    }

    @Test
    void aRenameOfARename_isFollowedToTheEnd() {
        assertThat(lookup.current("Стара назва", Unit.M2)).isEqualTo("нова назва");
    }

    @Test
    void theUnitIsPartOfTheKey_andANameNeverRenamedComesBackAsWritten() {
        assertThat(lookup.current("Монтаж маурлєта", Unit.M2)).isEqualTo("Монтаж маурлєта");
        assertThat(lookup.current("Своя робота", Unit.M2)).isEqualTo("Своя робота");
        assertThat(lookup.renamed("Своя робота", "M2")).isFalse();
    }

    @Test
    void aCycleSomeoneTypedByHand_stopsInsteadOfSpinning() {
        assertThat(lookup.current("Петля A", Unit.M2)).isIn("петля a", "петля b");
    }
}
