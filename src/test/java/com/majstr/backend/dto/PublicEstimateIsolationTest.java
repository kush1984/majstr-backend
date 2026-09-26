package com.majstr.backend.dto;

import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression guard for the object-economy isolation rule: anything served over a public share
 * token — the legacy per-estimate view AND the object-level portal (payments-economy-portal
 * iteration added a second public DTO tree, {@link PublicPortalView}, so both are walked; a
 * check rooted at only one would miss a leak on the other) — must NEVER carry object expenses,
 * profit, or internal economy fields. Reflects the whole record tree so a future accidental
 * field (e.g. an added `economy`) fails here instead of leaking to the client.
 */
class PublicEstimateIsolationTest {

    // «crew» and «sourceunitprice» joined the list with the crew-margin iteration. The reflection
    // walk below matches on SUBSTRINGS of a component name, and neither `crewMargin` nor
    // `sourceUnitPrice` contains any of the original five — so a leak of the бригадир's own prices,
    // the one number in this product the client must never see, would have passed this guard
    // silently. The serialization test beside it covers the same ground from the other end.
    private static final String[] FORBIDDEN =
            {"expense", "profit", "economy", "cost", "margin", "crew", "sourceunitprice"};
    // PublicActView (acts iteration) is a third public DTO tree — the client-facing view of one
    // signed act. Walked here too, so an accidental economy/note leak on it fails at build time.
    // The estimate PDF's model is walked here too (crew-margin iteration): it is the fourth thing a
    // client receives, it is assembled from the same entities, and nothing else would notice a crew
    // price added to it.
    private static final Class<?>[] PUBLIC_ROOTS =
            {PublicEstimateView.class, PublicPortalView.class, PublicActView.class,
             com.majstr.backend.service.EstimatePdfService.PdfModel.class};

    @Test
    void publicViewsCarryNoEconomyData() {
        for (Class<?> root : PUBLIC_ROOTS) {
            List<String> names = new ArrayList<>();
            collect(root, names, 0);
            assertThat(names).as("record components of %s", root.getSimpleName()).isNotEmpty();
            for (String name : names) {
                String lower = name.toLowerCase(Locale.ROOT);
                for (String bad : FORBIDDEN) {
                    assertThat(lower)
                            .as("%s must not expose '%s' (found component '%s')", root.getSimpleName(), bad, name)
                            .doesNotContain(bad);
                }
            }
        }
    }

    /**
     * Object NOTES (Нотатки) are private and must never reach the portal. The estimate's own
     * client-facing {@code notes} field is a String (type "String"), so we check component
     * TYPE names — a leaked {@code ProjectNote}/{@code NoteResponse}/{@code NoteView} record
     * would surface here, while the legitimate estimate-notes String stays clear.
     */
    @Test
    void publicViewsCarryNoObjectNoteType() {
        for (Class<?> root : PUBLIC_ROOTS) {
            List<String> typeNames = new ArrayList<>();
            collectTypes(root, typeNames, 0);
            assertThat(typeNames).as("record component types of %s", root.getSimpleName()).isNotEmpty();
            for (String type : typeNames) {
                assertThat(type.toLowerCase(Locale.ROOT))
                        .as("%s must not carry an object-note type (found '%s')", root.getSimpleName(), type)
                        .doesNotContain("note");
            }
        }
    }

    /**
     * Payments-economy-portal isolation: the portal's payments card sums only the SHARED
     * estimates and is a plain schedule (purpose/amount/dueDate/nextStage/status), plus unplanned
     * receipts as their own line items (label/amount/receivedAt) — it must never carry the
     * master's private aggregates (works/materials/spentReceipts/spentManual/cashBalance) that
     * {@code ObjectEconomyResponse} exposes on the owner-only side.
     */
    @Test
    void publicPortalPaymentsCardCarriesNoPrivateAggregates() {
        List<String> names = new ArrayList<>();
        collect(PublicPortalView.PaymentsCard.class, names, 0);
        // "received" appears twice (PaymentsCard's own aggregate + each PaymentRow's); "amount"
        // appears twice too (PaymentRow's + UnplannedReceiptRow's).
        // materialRefundApplied is the client's OWN money coming back for material (B-65) — it is
        // what makes `remaining` add up on his screen, and it tells him nothing he did not pay.
        assertThat(names).containsExactlyInAnyOrder("contractedTotal", "received", "remaining",
                "materialRefundApplied", "payments",
                "purpose", "amount", "received", "lastReceivedAt", "dueDate", "nextStage", "status",
                "unplannedReceipts", "label", "amount", "receivedAt");
    }

    private static void collectTypes(Class<?> type, List<String> typeNames, int depth) {
        if (type == null || !type.isRecord() || depth > 5) {
            return;
        }
        for (RecordComponent rc : type.getRecordComponents()) {
            typeNames.add(rc.getType().getSimpleName());
            collectTypes(rc.getType(), typeNames, depth + 1);
            if (rc.getGenericType() instanceof java.lang.reflect.ParameterizedType pt) {
                for (java.lang.reflect.Type arg : pt.getActualTypeArguments()) {
                    if (arg instanceof Class<?> c) {
                        typeNames.add(c.getSimpleName());
                        collectTypes(c, typeNames, depth + 1);
                    }
                }
            }
        }
    }

    private static void collect(Class<?> type, List<String> names, int depth) {
        if (type == null || !type.isRecord() || depth > 5) {
            return;
        }
        for (RecordComponent rc : type.getRecordComponents()) {
            names.add(rc.getName());
            collect(rc.getType(), names, depth + 1); // nested record
            // element type of List<...> components
            if (rc.getGenericType() instanceof java.lang.reflect.ParameterizedType pt) {
                for (java.lang.reflect.Type arg : pt.getActualTypeArguments()) {
                    if (arg instanceof Class<?> c) {
                        collect(c, names, depth + 1);
                    }
                }
            }
        }
    }
}
