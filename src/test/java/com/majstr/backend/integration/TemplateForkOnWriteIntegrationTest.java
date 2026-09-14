package com.majstr.backend.integration;

import com.majstr.backend.dto.EstimateCreateRequest;
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
    private UUID projectId;

    @BeforeEach
    void seed() {
        ownerId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (id, email, email_canonical, password_hash, full_name, phone,
                                   company_name, referral_code)
                VALUES (?, ?, ?, 'x', 'Майстер', '+380', 'ФОП', ?)
                """, ownerId, ownerId + "@t.ua", ownerId + "@t.ua", ownerId.toString().substring(0, 8));
        jdbc.update("INSERT INTO user_trades (user_id, trade) VALUES (?, 'PAINTER')", ownerId);

        projectId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO projects (id, owner_id, name, address, status)
                VALUES (?, ?, 'Квартира', 'вул. Тестова 1', 'IN_PROGRESS')
                """, projectId, ownerId);

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

    // ---- the request in flight still names the DEFAULT's item ids ----------------------------

    /**
     * The write that TRIGGERS the fork is the interesting one: the copy's positions are new rows
     * with new ids, while the request already in flight names the default's. Addressing a position
     * that does not exist in the copy made the removal a silent no-op — and the PWA, which re-seeds
     * its baseline from the answer, reported success. The master deleted a line, saw it go, and
     * found it back on the next load.
     */
    @Test
    void removingAPositionOfAnUntouchedDefault_landsInTheCopy() {
        UUID defaultItemId = itemId("Грунтування");

        var detail = service.removeItem(defaultId, defaultItemId, ownerId);

        assertThat(detail.id()).isNotEqualTo(defaultId);
        assertThat(detail.items()).extracting(i -> i.name()).containsExactly("Демонтаж", "Фарбування");
        // The shared row keeps all three — every other master still sees the bundle they had.
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM estimate_template_items WHERE template_id = ?",
                Integer.class, defaultId)).isEqualTo(3);
    }

    @Test
    void editingAPositionOfAnUntouchedDefault_landsInTheCopy() {
        UUID defaultItemId = itemId("Грунтування");

        var detail = service.updateItem(defaultId, defaultItemId,
                new TemplateItemRequest("Грунтування в два шари", ItemType.WORK, Unit.M2), ownerId);

        assertThat(detail.items()).extracting(i -> i.name())
                .containsExactly("Демонтаж", "Грунтування в два шари", "Фарбування");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM estimate_template_items
                WHERE template_id = ? AND name = 'Грунтування'
                """, Integer.class, defaultId)).as("the shared wording is untouched").isEqualTo(1);
    }

    @Test
    void reorderingAnUntouchedDefault_landsInTheCopy() {
        // A bundle is a SEQUENCE, so a drag that quietly does nothing is a lost edit, not a cosmetic
        // one: the order IS the content. Every id here is the default's.
        List<UUID> reversed = new ArrayList<>(
                List.of(itemId("Фарбування"), itemId("Грунтування"), itemId("Демонтаж")));

        var detail = service.reorderItems(defaultId, new TemplateItemsOrderRequest(reversed), ownerId);

        assertThat(detail.items()).extracting(i -> i.name())
                .containsExactly("Фарбування", "Грунтування", "Демонтаж");
        assertThat(detail.items()).extracting(i -> i.sortOrder()).containsExactly(0, 1, 2);
    }

    // ---- what the copy carries with it -------------------------------------------------------

    /**
     * The paragraph under the table (V121) is part of the bundle — it says what «Q4» IS. A copy
     * without it means a master who edits one position loses the wording, and since
     * {@code applyToProject} snapshots it into {@code estimates.quality_note}, every estimate built
     * from his copy afterwards would be missing the finish level he picked the bundle for.
     */
    @Test
    void theCopyInheritsTheBundlesDescription_andStillSnapshotsItOntoAnEstimate() {
        jdbc.update("UPDATE estimate_templates SET description = ? WHERE id = ?",
                "Поверхня під фарбування, рівень Q4.", defaultId);

        var fork = service.addItem(defaultId,
                new TemplateItemRequest("Шліфування", ItemType.WORK, Unit.M2), ownerId);

        assertThat(fork.description()).isEqualTo("Поверхня під фарбування, рівень Q4.");
        var estimate = service.applyToProject(projectId, fork.id(),
                new EstimateCreateRequest(null, null, "Кошторис"), ownerId);
        assertThat(jdbc.queryForObject("SELECT quality_note FROM estimates WHERE id = ?",
                String.class, estimate.id())).isEqualTo("Поверхня під фарбування, рівень Q4.");
    }

    /**
     * «Видалити» on a default the master has ALREADY edited must not forget the copy. The override
     * link is the only way back to it — the fork carries no pointer to the default it came from —
     * so nulling it would hide the default and leave an orphan bundle in his list that nothing can
     * ever resolve to again.
     */
    @Test
    void deletingTheDefaultAfterItWasForked_keepsPointingAtTheCopy() {
        UUID forkId = service.addItem(defaultId,
                new TemplateItemRequest("Шліфування", ItemType.WORK, Unit.M2), ownerId).id();

        service.delete(defaultId, ownerId);

        assertThat(jdbc.queryForObject("""
                SELECT forked_template_id FROM template_default_override
                WHERE user_id = ? AND template_id = ?
                """, UUID.class, ownerId, defaultId)).isEqualTo(forkId);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM estimate_templates WHERE id = ?", Integer.class, forkId))
                .as("his own bundle is not what he deleted").isEqualTo(1);
        User user = userRepository.findWithTradesById(ownerId).orElseThrow();
        assertThat(service.listForUser(user)).extracting(s -> s.id())
                .contains(forkId)
                .doesNotContain(defaultId);
    }

    /** The default's own position ids, as a client that opened the bundle before any edit has them. */
    private UUID itemId(String name) {
        return jdbc.queryForObject(
                "SELECT id FROM estimate_template_items WHERE template_id = ? AND name = ?",
                UUID.class, defaultId, name);
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
