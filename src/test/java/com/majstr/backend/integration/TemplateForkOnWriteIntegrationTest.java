package com.majstr.backend.integration;

import com.majstr.backend.dto.TemplateItemRequest;
import com.majstr.backend.dto.TemplateItemsOrderRequest;
import com.majstr.backend.entity.ItemType;
import com.majstr.backend.entity.Trade;
import com.majstr.backend.entity.Unit;
import com.majstr.backend.entity.User;
import com.majstr.backend.repository.UserRepository;
import com.majstr.backend.service.EstimateTemplateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Copy-on-write on a SYSTEM DEFAULT template (V113), end to end against a real database.
 *
 * <p>Mockito cannot see any of this: that the fork really lands as an owned row (the
 * {@code is_default ⇔ owner_id IS NULL} CHECK from V28 would reject a half-built copy), that
 * every position comes across, that {@code template_default_override} takes the default out of
 * this master's list, and — the reason the FK is written the way it is — that deleting the copy
 * later leaves the default HIDDEN via {@code ON DELETE SET NULL} rather than silently restored.</p>
 */
class TemplateForkOnWriteIntegrationTest extends IntegrationTestBase {

    @Autowired JdbcTemplate jdbc;
    @Autowired EstimateTemplateService service;
    @Autowired UserRepository userRepository;

    private UUID ownerId;
    private UUID defaultId;

    @BeforeEach
    void seed() {
        ownerId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (id, email, email_canonical, password_hash, full_name, phone,
                                   company_name, referral_code)
                VALUES (?, ?, ?, 'x', 'Майстер', '+380', 'ФОП', ?)
                """, ownerId, ownerId + "@t.ua", ownerId + "@t.ua", ownerId.toString().substring(0, 8));
        jdbc.update("INSERT INTO user_trades (user_id, trade) VALUES (?, 'PAINTER')", ownerId);

        defaultId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO estimate_templates (id, owner_id, name, trade, is_default)
                VALUES (?, NULL, 'Тестовий стандартний шаблон', 'PAINTER', TRUE)
                """, defaultId);
        insertItem(defaultId, "Демонтаж", 0);
        insertItem(defaultId, "Грунтування", 1);
        insertItem(defaultId, "Фарбування", 2);
    }

    private void insertItem(UUID templateId, String name, int sort) {
        jdbc.update("""
                INSERT INTO estimate_template_items (id, template_id, name, type, unit, sort_order)
                VALUES (?, ?, ?, 'WORK', 'M2', ?)
                """, UUID.randomUUID(), templateId, name, sort);
    }

    @Test
    void editingASystemDefault_forksItIntoAnOwnedCopyAndRetiresTheOriginal() {
        var detail = service.addItem(defaultId,
                new TemplateItemRequest("Шліфування", ItemType.WORK, Unit.M2), ownerId);

        assertThat(detail.id()).as("the write answers with the copy, not the default").isNotEqualTo(defaultId);
        assertThat(detail.isDefault()).isFalse();
        assertThat(detail.name()).isEqualTo("Тестовий стандартний шаблон");
        assertThat(detail.trade()).isEqualTo(Trade.PAINTER);
        assertThat(detail.items()).extracting(i -> i.name())
                .containsExactly("Демонтаж", "Грунтування", "Фарбування", "Шліфування");

        assertThat(jdbc.queryForObject(
                "SELECT owner_id FROM estimate_templates WHERE id = ?", UUID.class, detail.id()))
                .isEqualTo(ownerId);
        assertThat(jdbc.queryForObject("""
                SELECT forked_template_id FROM template_default_override
                WHERE user_id = ? AND template_id = ?
                """, UUID.class, ownerId, defaultId))
                .isEqualTo(detail.id());

        // The shared row is untouched — every other master still sees the bundle they always had.
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM estimate_template_items WHERE template_id = ?
                """, Integer.class, defaultId)).isEqualTo(3);

        User user = userRepository.findWithTradesById(ownerId).orElseThrow();
        assertThat(service.listForUser(user)).extracting(s -> s.id())
                .contains(detail.id())
                .doesNotContain(defaultId);
    }

    @Test
    void reorderingASystemDefault_forksItAndStoresTheNewSequence() {
        var forked = service.get(
                service.addItem(defaultId, new TemplateItemRequest("Шліфування", ItemType.WORK, Unit.M2), ownerId)
                        .id(), ownerId);
        List<UUID> reversed = new ArrayList<>(forked.items().stream().map(i -> i.id()).toList());
        Collections.reverse(reversed);

        var after = service.reorderItems(forked.id(), new TemplateItemsOrderRequest(reversed), ownerId);

        assertThat(after.items()).extracting(i -> i.name())
                .containsExactly("Шліфування", "Фарбування", "Грунтування", "Демонтаж");
        assertThat(after.items()).extracting(i -> i.sortOrder()).containsExactly(0, 1, 2, 3);
    }

    @Test
    void deletingASystemDefault_hidesItWithoutTouchingTheSharedRow() {
        service.delete(defaultId, ownerId);

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM estimate_templates WHERE id = ?", Integer.class, defaultId))
                .as("shared by every master — never really deleted").isEqualTo(1);
        User user = userRepository.findWithTradesById(ownerId).orElseThrow();
        assertThat(service.listForUser(user)).extracting(s -> s.id()).doesNotContain(defaultId);

        service.restoreDefaults(ownerId);
        assertThat(service.listForUser(userRepository.findWithTradesById(ownerId).orElseThrow()))
                .extracting(s -> s.id()).contains(defaultId);
    }

    @Test
    void deletingTheCopy_leavesTheDefaultHidden_viaOnDeleteSetNull() {
        UUID forkId = service.addItem(defaultId,
                new TemplateItemRequest("Шліфування", ItemType.WORK, Unit.M2), ownerId).id();

        service.delete(forkId, ownerId);

        // The master threw that bundle away twice over; restoring it behind their back would be
        // a surprise. The row survives with a null pointer, so the default stays out of the list.
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM template_default_override WHERE user_id = ? AND template_id = ?
                """, Integer.class, ownerId, defaultId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT forked_template_id FROM template_default_override
                WHERE user_id = ? AND template_id = ?
                """, UUID.class, ownerId, defaultId)).isNull();
        User user = userRepository.findWithTradesById(ownerId).orElseThrow();
        assertThat(service.listForUser(user)).extracting(s -> s.id()).doesNotContain(defaultId);
    }

    @Test
    void aSecondEditOfTheSameDefault_landsInTheSameCopy() {
        UUID first = service.addItem(defaultId,
                new TemplateItemRequest("Шліфування", ItemType.WORK, Unit.M2), ownerId).id();
        UUID second = service.addItem(defaultId,
                new TemplateItemRequest("Обезпилення", ItemType.WORK, Unit.M2), ownerId).id();

        assertThat(second).isEqualTo(first);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM estimate_templates WHERE owner_id = ?
                """, Integer.class, ownerId)).isEqualTo(1);
    }
}
