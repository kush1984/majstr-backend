package com.majstr.backend.entity;

/**
 * What a {@link WorkActItem} IS — recorded on the row rather than inferred from its foreign keys
 * (review B-55).
 *
 * <p>Until the adjustment line existed, «no {@code estimate_item_id}» meant «an additional work not
 * in any estimate», and {@code ActAddendumCreator} rolled every such row into a SIGNED ADDENDUM so
 * «За договором» would absorb it. An ADJUSTMENT closes no position either, yet it already belongs
 * to an estimate whose «%» line put that money into «За договором» once — rolling it up would bill
 * it twice.</p>
 */
public enum WorkActLineKind {

    /** Closes a position of a signed estimate; carries both ids. */
    ESTIMATE,

    /** Off-estimate work the client accepts on the act itself; carries neither id. The ADDENDUM is
     *  what puts it into «За договором». */
    ADDITIONAL,

    /** This act's share of an estimate's discounts and surcharges — server-authored, one per
     *  estimate and type. Carries {@code estimate_id} but no {@code estimate_item_id}. */
    ADJUSTMENT
}
