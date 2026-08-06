package com.majstr.backend.service;

import com.majstr.backend.entity.EstimateItem;
import com.majstr.backend.entity.ItemType;
import com.majstr.backend.entity.PercentBaseKind;
import com.majstr.backend.entity.Unit;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * «%» as a share OF something, and the three-step pass that makes it computable in one go.
 *
 * <p>Before this, a PERCENT line multiplied like any other unit: 10 × 500 = 5 000 ₴, shown as
 * «10 % · 500 ₴/%» — five hundred hryvnia for one percent, which means nothing. Every case below is
 * a rule the master can state in one sentence, which is the bar for arithmetic he quotes from.</p>
 */
class EstimateMathTest {

    private static EstimateItem line(String name, Unit unit, String qty, String price) {
        EstimateItem item = EstimateItem.builder()
                .id(UUID.randomUUID())
                .type(ItemType.WORK)
                .name(name)
                .unit(unit)
                .quantity(new BigDecimal(qty))
                .unitPrice(new BigDecimal(price))
                .build();
        return item;
    }

    private static EstimateItem percentOf(String name, String pct, EstimateItem base) {
        EstimateItem item = line(name, Unit.PERCENT, pct, "0");
        item.setPercentBaseKind(PercentBaseKind.POSITION);
        item.setPercentBaseItemId(base.getId());
        return item;
    }

    @Test
    void aManualBaseIsThePriceColumn() {
        EstimateItem item = line("Непередбачені", Unit.PERCENT, "10", "500");
        item.setPercentBaseKind(PercentBaseKind.MANUAL);

        EstimateMath.recalculate(List.of(item));

        assertThat(item.getLineTotal()).isEqualByComparingTo("50.00");
    }

    @Test
    void aPercentOfAnotherLineFollowsThatLine() {
        EstimateItem wardrobe = line("Шафа", Unit.PIECE, "1", "1000");
        EstimateItem delivery = percentOf("Доставка", "10", wardrobe);

        EstimateMath.recalculate(List.of(wardrobe, delivery));
        assertThat(delivery.getLineTotal()).isEqualByComparingTo("100.00");

        // The live link is the whole point: a base that gets dearer takes the percentage with it.
        // Freezing the number here would leave «10 %» charging a tenth of a price nobody quotes.
        wardrobe.setUnitPrice(new BigDecimal("1200"));
        EstimateMath.recalculate(List.of(wardrobe, delivery));
        assertThat(delivery.getLineTotal()).isEqualByComparingTo("120.00");
    }

    @Test
    void severalTOTALlinesAreAllMeasuredAgainstTheSameBase() {
        // Compounding them would make the answer depend on which was typed first, and no master
        // could explain that to a client.
        EstimateItem work = line("Робота", Unit.M2, "1", "1000");
        EstimateItem transport = line("Транспортні", Unit.PERCENT, "20", "0");
        transport.setPercentBaseKind(PercentBaseKind.TOTAL);
        EstimateItem extras = line("Непередбачені", Unit.PERCENT, "10", "0");
        extras.setPercentBaseKind(PercentBaseKind.TOTAL);

        EstimateMath.Totals totals = EstimateMath.recalculate(List.of(work, transport, extras));

        assertThat(transport.getLineTotal()).isEqualByComparingTo("200.00");
        assertThat(extras.getLineTotal()).isEqualByComparingTo("100.00");   // not 10 % of 1 200
        assertThat(totals.total()).isEqualByComparingTo("1300.00");
    }

    @Test
    void aTOTALlineCountsThePercentagesOfLINESinItsBase_butNotOtherTOTALs() {
        // Step 2 finishes before step 3 starts, which is what makes the order a rule rather than an
        // accident: «20 % від усього» includes the delivery percentage, because that is part of what
        // the sheet costs.
        EstimateItem wardrobe = line("Шафа", Unit.PIECE, "1", "1000");
        EstimateItem delivery = percentOf("Доставка", "10", wardrobe);   // 100
        EstimateItem transport = line("Транспортні", Unit.PERCENT, "20", "0");
        transport.setPercentBaseKind(PercentBaseKind.TOTAL);

        EstimateMath.recalculate(List.of(wardrobe, delivery, transport));

        assertThat(transport.getLineTotal()).isEqualByComparingTo("220.00");   // 20 % of 1 100
    }

    @Test
    void aDetachedLineKeepsItsMoneyAndIsNeverRecomputed() {
        // Manual wins over automatic — the rule quantityManual already follows in measurements.
        EstimateItem wardrobe = line("Шафа", Unit.PIECE, "1", "1000");
        EstimateItem delivery = percentOf("Доставка", "10", wardrobe);
        EstimateMath.recalculate(List.of(wardrobe, delivery));

        delivery.setBaseDetached(true);
        wardrobe.setUnitPrice(new BigDecimal("5000"));
        EstimateMath.recalculate(List.of(wardrobe, delivery));

        assertThat(delivery.getLineTotal()).isEqualByComparingTo("100.00");
    }

    @Test
    void aBaseThatIsGoneLeavesTheLastAmountStanding_notZero() {
        // Deleting «Шафа» must not quietly zero the delivery the master is charging for. The row
        // keeps its money and the screen says the base is gone.
        EstimateItem wardrobe = line("Шафа", Unit.PIECE, "1", "1000");
        EstimateItem delivery = percentOf("Доставка", "10", wardrobe);
        EstimateMath.recalculate(List.of(wardrobe, delivery));

        EstimateMath.recalculate(List.of(delivery));   // the base is no longer in the estimate

        assertThat(delivery.getLineTotal()).isEqualByComparingTo("100.00");
    }

    @Test
    void aNegativePercentIsADiscount_andItLowersTheTotal() {
        // «дав знижку — менше заробив»: a negative percent line is a discount off its base, its
        // line amount comes out negative, and the subtotal drops by exactly that. No clamp anywhere.
        EstimateItem work = line("Робота", Unit.M2, "1", "1000");
        EstimateItem discount = line("Знижка", Unit.PERCENT, "-15", "0");
        discount.setPercentBaseKind(PercentBaseKind.TOTAL);

        EstimateMath.Totals totals = EstimateMath.recalculate(List.of(work, discount));

        assertThat(discount.getLineTotal()).isEqualByComparingTo("-150.00"); // −15 % of 1 000
        assertThat(totals.total()).isEqualByComparingTo("850.00");
    }

    @Test
    void aPercentOfTheEstimateMeasuresOnlyItsOwnType() {
        // «% від кошторису» is split by type: a WORK percent moves the works subtotal, a MATERIAL
        // percent the materials subtotal — the same split the master reads on the summary card. A
        // WORK 10 % measuring 3 000 (works + materials) instead of 1 000 would be the «косячок».
        EstimateItem work = line("Робота", Unit.M2, "1", "1000");        // WORK 1 000
        EstimateItem material = line("Плитка", Unit.M2, "1", "2000");    // MATERIAL 2 000
        material.setType(ItemType.MATERIAL);
        EstimateItem worksMarkup = line("Складність", Unit.PERCENT, "10", "0");
        worksMarkup.setPercentBaseKind(PercentBaseKind.TOTAL);           // +10 % of works only
        EstimateItem materialsDiscount = line("Знижка", Unit.PERCENT, "-5", "0");
        materialsDiscount.setType(ItemType.MATERIAL);
        materialsDiscount.setPercentBaseKind(PercentBaseKind.TOTAL);     // −5 % of materials only

        EstimateMath.Totals totals = EstimateMath.recalculate(
                List.of(work, material, worksMarkup, materialsDiscount));

        assertThat(worksMarkup.getLineTotal()).isEqualByComparingTo("100.00");        // 10 % of 1 000
        assertThat(materialsDiscount.getLineTotal()).isEqualByComparingTo("-100.00"); // −5 % of 2 000
        assertThat(totals.works()).isEqualByComparingTo("1100.00");
        assertThat(totals.materials()).isEqualByComparingTo("1900.00");
        assertThat(totals.total()).isEqualByComparingTo("3000.00");
    }

    @Test
    void ordinaryLinesAreUntouched_andTheSubtotalsStillSplitByType() {
        EstimateItem work = line("Робота", Unit.M2, "25", "180");
        EstimateItem material = line("Плитка", Unit.M2, "10", "500");
        material.setType(ItemType.MATERIAL);

        EstimateMath.Totals totals = EstimateMath.recalculate(List.of(work, material));

        assertThat(work.getLineTotal()).isEqualByComparingTo("4500.00");
        assertThat(totals.works()).isEqualByComparingTo("4500.00");
        assertThat(totals.materials()).isEqualByComparingTo("5000.00");
        assertThat(totals.total()).isEqualByComparingTo("9500.00");
    }

    @Test
    void roundingIsPerLine_soTheLinesAddUpToTheTotalExactly() {
        // 33,33 % of 100 is 33,33 — and three of them are 99,99, not 100. A master checking the
        // sheet by hand has to arrive at the same number we print.
        EstimateItem base = line("База", Unit.PIECE, "1", "100");
        List<EstimateItem> items = List.of(
                base,
                percentOf("A", "33.33", base),
                percentOf("B", "33.33", base),
                percentOf("C", "33.33", base));

        EstimateMath.Totals totals = EstimateMath.recalculate(items);

        assertThat(totals.total()).isEqualByComparingTo("199.99");
    }
}
