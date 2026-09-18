package com.majstr.backend.dto;

import com.majstr.backend.entity.CashCategory;
import com.majstr.backend.entity.CashDirection;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Write one line of the master's own money (V135).
 *
 * <p><b>Adding here NEVER asks about an object</b> (master's ruling, round 3: «я хочу, щоб
 * гроші/доходи зразу брались з обʼєктів, а не щось там вибиралось»). Money that belongs to an object
 * arrives on the READ path all by itself — it is already in that object's journal. What he types
 * here is what no object knows about: fuel, tools, taxes, income for work that closed without an
 * act. An earlier round offered an object picker that ROUTED the row into that object's journal; it
 * was removed because it read as a required step in front of a screen that already pulls
 * everything.</p>
 *
 * <p>{@code happenedOn} may be back-dated freely — «не завжди майстер вносить одразу». The TIME is
 * never sent: it is stamped server-side at entry, because its only job is ordering rows inside a day
 * and a time picker on every entry is friction for nothing else.</p>
 *
 * <p>The same record is also the EDIT body for a row of any kind, including an object's own payment
 * or expense — {@code kind} says which table the row lives in. Fields that table has no place for
 * are ignored rather than refused: a payment has no category, and its direction is decided by being
 * a payment at all.</p>
 *
 * @param materialRefund only meaningful on an INCOME; a DB CHECK refuses it on a personal expense
 * @param kind           required on an edit, ignored on a create (which is always PERSONAL)
 */
public record CashEntryRequest(
        @NotNull CashDirection direction,
        @NotNull @DecimalMin(value = "0.0", inclusive = false) @DecimalMax("100000000") BigDecimal amount,
        /** Optional by design — a master at the wheel will not pick one. */
        CashCategory category,
        @Size(max = 500) String note,
        /** Absent = today, resolved in Europe/Kyiv (never the server's UTC idea of today). */
        LocalDate happenedOn,
        boolean materialRefund,
        CashEntryKind kind
) {}
