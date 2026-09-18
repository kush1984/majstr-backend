package com.majstr.backend.entity;

/**
 * Coarse bucket for the master's own cash flow («Мої гроші»), matching the DB CHECK.
 *
 * <p><b>The set deliberately CONTAINS the object journal's three</b> ({@link ExpenseCategory}):
 * {@link #MATERIALS} is its MATERIALS, {@link #CREW} its LABOR, {@link #OTHER} its OTHER. The cash
 * screen groups object expenses and personal rows into one summary, and a vocabulary that did not
 * line up would make that grouping a lie.</p>
 *
 * <p>The rest exist because they are what an object never knows about: the van, the tools, the tax
 * office. Rent, advertising and phone bills are deliberately absent — rarer, and a note carries them
 * fine. Six buttons is what fits a phone; a seventh gets added when a master asks for it by name.</p>
 *
 * <p><b>Always optional.</b> A master at the wheel will not pick a category, so the note has to
 * stand on its own — and an object payment has no category at all.</p>
 */
public enum CashCategory {
    // --- money out (the first three mirror ExpenseCategory) ---
    MATERIALS,
    CREW,
    FUEL,
    TOOLS,
    TAXES,
    // --- money in ---
    ADVANCE,
    WORK,
    // --- either ---
    OTHER;

    /**
     * How this lands in an object's journal when the master picked an object.
     *
     * <p>Everything the object has no bucket for becomes OTHER — a van's fuel logged against a flat
     * is «інше» there, which is true. Never invent a finer object category than the object has.</p>
     */
    public ExpenseCategory toExpenseCategory() {
        return switch (this) {
            case MATERIALS -> ExpenseCategory.MATERIALS;
            case CREW -> ExpenseCategory.LABOR;
            default -> ExpenseCategory.OTHER;
        };
    }
}
