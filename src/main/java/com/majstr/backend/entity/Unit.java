package com.majstr.backend.entity;

public enum Unit {
    M2,
    M,
    LINEAR_METER,
    PIECE,
    KG,
    HOUR,
    SET,
    M3,
    T,
    POINT,
    PERCENT,
    KM,
    /** День роботи — how small or open-ended jobs are actually quoted. Not 8 × HOUR. */
    DAY,
    /** Поверх — carrying material up a building with no lift is priced per floor. */
    FLOOR,
    /** Літр — primer, paint and adhesive are bought and consumed by volume (V126). */
    LITRE,
    /*
     * The shop's own counts (V229): «три мішки клею» was filed as «3 шт» because a bag had no unit. Short labels
     * like «шт» — «3 міш.», never a declined «3 мішки» — so every surface reads them after any number.
     */
    /** Мішок — adhesive, plaster, putty, cement. */
    BAG,
    /** Пачка / упаковка — grout, screws, plugs, spacers. */
    PACK,
    /** Відро — paint, primer, mastic. */
    BUCKET,
    /** Каністра — primer, antiseptic, waterproofing liquid. */
    CANISTER,
    /** Рулон — mesh, tape, membrane, wallpaper. */
    ROLL,
    /** Лист — plasterboard, OSB, plywood. */
    SHEET
}
