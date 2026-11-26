package com.majstr.backend.service.catalog;

import com.majstr.backend.entity.Unit;
import com.majstr.backend.service.NameKeys;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Where a library wording went when the trade reviews renamed or merged it (V158 onwards,
 * {@code catalog_renamed_position}: old name → new name, per unit). A master who already owned the new
 * wording kept his old copy too (V167), and his old estimate lines keep the old name for good — so a
 * price under the old wording is a price for the new one.
 *
 * <p>Read by the market figures (plan-regional-prices.md, 2026-11-26): the weekly medians count an old
 * wording as the new one, and the price hint and «Ціни по областях» answer an old wording with the new
 * one's figures. Only what WE recorded — never a «similar» name. Keys are {@link NameKeys#of}, the same
 * fold the migrations wrote the table with.</p>
 */
@Component
@RequiredArgsConstructor
public class CatalogRenames {

    /** A rename of a rename is followed; more hops than this would be a cycle someone typed by hand. */
    private static final int MAX_HOPS = 10;

    private final JdbcTemplate jdbc;

    /** The whole table at once (a few hundred rows) — for the weekly runs and the per-catalog screen. */
    public Lookup load() {
        Map<String, String> next = new HashMap<>();
        jdbc.query("SELECT old_name_key, new_name_key, unit FROM catalog_renamed_position",
                rs -> {
                    next.put(rs.getString("old_name_key") + '|' + rs.getString("unit"), rs.getString("new_name_key"));
                });
        return new Lookup(next);
    }

    /** A snapshot of the table. */
    public static final class Lookup {

        public static final Lookup EMPTY = new Lookup(Map.of());

        private final Map<String, String> next;

        Lookup(Map<String, String> next) {
            this.next = next;
        }

        /** The wording the library uses now — the name itself (as written) when it was never renamed. */
        public String current(String name, String unit) {
            String key = NameKeys.of(name);
            String to = next.get(key + '|' + unit);
            if (to == null) {
                return name;
            }
            for (int hop = 1; hop < MAX_HOPS; hop++) {
                String further = next.get(to + '|' + unit);
                if (further == null) {
                    break;
                }
                to = further;
            }
            return to;
        }

        public String current(String name, Unit unit) {
            return current(name, unit.name());
        }

        public boolean renamed(String name, String unit) {
            return next.containsKey(NameKeys.of(name) + '|' + unit);
        }
    }
}
