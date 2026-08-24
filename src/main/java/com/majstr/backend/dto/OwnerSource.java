package com.majstr.backend.dto;

import java.util.UUID;

/**
 * Spring Data projection for the two "who ever shared" queries behind the funnel's {@code shared}
 * step: one master id plus their referral source.
 *
 * <p>Id AND source in the same row on purpose. The step is a UNION over two link tables (a master
 * commonly holds both kinds), so the ids have to be de-duplicated in Java before anything is
 * counted — which means the by-source breakdown cannot be a {@code GROUP BY} in SQL either. Coming
 * back for the sources in a second query would let the aggregate and the breakdown drift apart;
 * carrying both here makes them one computation.</p>
 */
public interface OwnerSource {
    UUID getOwnerId();
    String getSource();
}
