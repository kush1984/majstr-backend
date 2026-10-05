package com.majstr.backend.dto;

import java.math.BigDecimal;

/**
 * <p><b>NEVER SENT. {@code ObjectEconomyResponse.internals} is always null</b> (review B-85).</p>
 *
 * <p>The PWA dropped «Прибуток/Витрати» and the object expense journal for good with the crew-margin
 * iteration: this formula subtracts expenses the app gives the master no way to enter against an
 * object, so it read ≈ «За договором» for everyone. It went on being COMPUTED for a while — an
 * aggregate query on every economy request for a figure with no reader — and now it is not. The
 * field itself stays on the response because the PWA's hand-written types declare it as nullable, so
 * a null needs no change there, and the record stays because this javadoc is the record of the
 * decision. «Скільки я заробив» is answered by «Мої гроші»; the object answers «скільки моя націнка
 * над бригадою» ({@code CrewMarginResponse}).</p>
 *
 * <p>Everything below describes the shape as it last stood.</p>
 *
 * PRO-only internal economy — the master's real earnings, distinct from {@code payments} (FREE,
 * "what came in from the client"). See {@link ObjectEconomyResponse} for how the two combine.
 *
 * <p><b>Economy-rework iteration:</b> deliberately just two numbers now. The earlier version split
 * income into works/materials and modelled a separate "materials cash pot" (received − receipts),
 * which required a duplicate estimate's margin to be computed as a difference against its own
 * parent — that difference formula went <em>negative</em> for a discount duplicate once its parent
 * stopped existing as a live deal (see {@code superseded_by_estimate_id}, V95). Money doesn't split
 * by category here: what the master actually pays out — crew wages included — is logged as an
 * {@code object_expense} (any category), so {@code profit} already nets it out without needing to
 * know whether an estimate was a plain sheet or a marked-up/discounted duplicate of another.</p>
 *
 * <ul>
 *   <li>{@code expenses} — Σ every {@code object_expense} on the object, any category/source.</li>
 *   <li>{@code profit} = {@code contracted(counted) − expenses}, where "contracted" is
 *       {@code payments.contractedTotal()} — the same figure the FREE payments block already
 *       shows, so this never disagrees with it.</li>
 * </ul>
 */
public record ObjectEconomyInternalsResponse(
        BigDecimal expenses,
        BigDecimal profit
) {}
