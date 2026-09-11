package com.majstr.backend.dto;

import java.util.List;

/**
 * «Порахували матеріали для: Гіпсокартон, Малярні роботи» — what the calculation actually answered
 * for, said in the master's own words (his ruling, 2026-09-11).
 *
 * <p>It replaces a ratio and a list. The first draft said «Порахували 8 з 39 позицій» and named the
 * 31 it had not: on a real estimate that filled the screen with every demolition, cleanup and
 * callout line — work that consumes no material and never could — and читалось як поломка. Two
 * things were wrong with it. The denominator counted lines that are not buying decisions at all
 * (see {@code MaterialCalculatorService#workLines}), and a position we have no figure for is not a
 * confession worth 31 lines of amber text: the master already knows his own estimate.</p>
 *
 * <p>So the report names the TRADES of the positions it counted, and nothing else. The trade comes
 * from the POSITION, not from the norm that matched it: a norm with no trade of its own answers for
 * anyone, and naming ITS trade would leave the line blank — or, worse, would answer «Гіпсокартон»
 * for a position the master filed under something else.</p>
 *
 * @param trades     trade codes ({@code Trade} names), in the order the estimate first mentions
 *                   them, for positions a norm answered for. Empty means nothing was counted —
 *                   the screen says so in one line instead of showing a ratio of zero
 * @param otherWorks a counted position carries no trade at all. {@code estimate_items.trade} is
 *                   nullable by design (V125) and older lines predate it, so this is an ordinary
 *                   case, not a defect — and it is its own flag rather than {@code Trade.OTHER}
 *                   because V125 keeps NULL and OTHER deliberately distinct
 */
public record MaterialCoverage(List<String> trades, boolean otherWorks) {}
