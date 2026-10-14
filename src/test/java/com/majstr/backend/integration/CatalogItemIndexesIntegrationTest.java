package com.majstr.backend.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V170 — the two single-column indexes are gone, and every owner-scoped query still has an index
 * that leads with {@code owner_id} (the ON DELETE CASCADE from {@code users} needs one too).
 */
class CatalogItemIndexesIntegrationTest extends IntegrationTestBase {

    @Autowired JdbcTemplate jdbc;

    private List<String> indexes() {
        return jdbc.queryForList("SELECT indexname FROM pg_indexes WHERE tablename = 'catalog_items'",
                String.class);
    }

    @Test
    void theRedundantIndexesAreDropped() {
        assertThat(indexes()).doesNotContain("idx_catalog_items_owner_id", "idx_catalog_items_type");
    }

    @Test
    void ownerScopedAccessKeepsAnIndexLeadingWithTheOwner() {
        assertThat(indexes()).contains("idx_catalog_items_owner_category", "idx_catalog_items_owner_sort",
                "ux_catalog_items_owner_name_type_unit");
        assertThat(jdbc.queryForList("""
                SELECT indexname FROM pg_indexes WHERE tablename = 'catalog_items'
                   AND indexdef LIKE '%USING btree (owner_id,%'""", String.class))
                .hasSizeGreaterThanOrEqualTo(3);
    }
}
