package com.majstr.backend.service.importer;

import com.majstr.backend.entity.Unit;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UnitNormalizerTest {

    @Test
    void recognizesCommonSynonyms() {
        assertThat(UnitNormalizer.normalize("кв.м")).isEqualTo(Unit.M2);
        assertThat(UnitNormalizer.normalize("кв м")).isEqualTo(Unit.M2);
        assertThat(UnitNormalizer.normalize("м²")).isEqualTo(Unit.M2);
        assertThat(UnitNormalizer.normalize("м2")).isEqualTo(Unit.M2);
        assertThat(UnitNormalizer.normalize("пог.м")).isEqualTo(Unit.LINEAR_METER);
        assertThat(UnitNormalizer.normalize("м.п.")).isEqualTo(Unit.LINEAR_METER);
        assertThat(UnitNormalizer.normalize("мп")).isEqualTo(Unit.LINEAR_METER);
        assertThat(UnitNormalizer.normalize("шт.")).isEqualTo(Unit.PIECE);
        assertThat(UnitNormalizer.normalize("м³")).isEqualTo(Unit.M3);
        assertThat(UnitNormalizer.normalize("куб.м")).isEqualTo(Unit.M3);
        assertThat(UnitNormalizer.normalize("т")).isEqualTo(Unit.T);
        assertThat(UnitNormalizer.normalize("кг")).isEqualTo(Unit.KG);
        assertThat(UnitNormalizer.normalize("год")).isEqualTo(Unit.HOUR);
        assertThat(UnitNormalizer.normalize("компл")).isEqualTo(Unit.SET);
        assertThat(UnitNormalizer.normalize("м")).isEqualTo(Unit.M);
        assertThat(UnitNormalizer.normalize("точка")).isEqualTo(Unit.POINT);
    }

    @Test
    void recognizesSquareMetreKilometreAndCount() {
        assertThat(UnitNormalizer.normalize("м.кв.")).isEqualTo(Unit.M2); // "м.кв." → мкв
        assertThat(UnitNormalizer.normalize("м.кв")).isEqualTo(Unit.M2);
        assertThat(UnitNormalizer.normalize("км")).isEqualTo(Unit.KM);
        assertThat(UnitNormalizer.normalize("km")).isEqualTo(Unit.KM);
        assertThat(UnitNormalizer.normalize("кількість")).isEqualTo(Unit.PIECE);
        assertThat(UnitNormalizer.normalize("к-сть")).isEqualTo(Unit.PIECE); // hyphen stripped → ксть
        assertThat(UnitNormalizer.normalize("к-ть")).isEqualTo(Unit.PIECE);
    }

    @Test
    void plainMetreVsLinearMetreDoNotCollide() {
        assertThat(UnitNormalizer.normalize("м")).isEqualTo(Unit.M);
        assertThat(UnitNormalizer.normalize("м.п.")).isEqualTo(Unit.LINEAR_METER);
    }

    @Test
    void unknownOrBlankIsNull() {
        assertThat(UnitNormalizer.normalize("бла")).isNull();
        assertThat(UnitNormalizer.normalize("")).isNull();
        assertThat(UnitNormalizer.normalize(null)).isNull();
    }
}
