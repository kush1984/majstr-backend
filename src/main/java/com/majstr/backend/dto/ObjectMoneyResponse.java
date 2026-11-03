package com.majstr.backend.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * «Гроші обʼєкта» — the same lens as «Мої гроші», narrowed to one object over its whole life.
 *
 * <p>Not a second formula: every figure is the cash feed's own, filtered by {@code projectId}, so
 * the object card and «Мої гроші» with this object picked can never disagree.</p>
 *
 * @param income       payment for the work that arrived on this object
 * @param expense      the master's own spending on it
 * @param earned       {@code income − expense}
 * @param materials    the client's material balance on this object — always present, zero included
 * @param crewRecorded whether ANY crew expense was recorded here. «Прибуток» was pulled twice
 *                     because a бригадир who forgot to log his crew read his gross as his take; the
 *                     card now says so instead of staying silent
 * @param entries      newest first, the object's rows of the cash feed
 * @param truncated    the list was cut at the server's cap; the totals never are
 */
public record ObjectMoneyResponse(
        BigDecimal income,
        BigDecimal expense,
        BigDecimal earned,
        CashFlowResponse.MaterialBalance materials,
        boolean crewRecorded,
        List<CashFlowResponse.Entry> entries,
        boolean truncated
) {}
