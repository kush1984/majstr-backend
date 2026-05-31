package com.majstr.backend.dev;

import com.majstr.backend.entity.Client;
import com.majstr.backend.entity.Estimate;
import com.majstr.backend.entity.EstimateItem;
import com.majstr.backend.entity.EstimateStatus;
import com.majstr.backend.entity.ItemType;
import com.majstr.backend.entity.Plan;
import com.majstr.backend.entity.Project;
import com.majstr.backend.entity.ProjectStatus;
import com.majstr.backend.entity.Role;
import com.majstr.backend.entity.Trade;
import com.majstr.backend.entity.Unit;
import com.majstr.backend.entity.User;
import com.majstr.backend.repository.ClientRepository;
import com.majstr.backend.repository.EstimateItemRepository;
import com.majstr.backend.repository.EstimateRepository;
import com.majstr.backend.repository.ProjectRepository;
import com.majstr.backend.repository.UserRepository;
import com.majstr.backend.service.CatalogTemplateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Seeds a small set of ready-to-use accounts on application startup —
 * but only when the {@code dev} profile is active. Idempotent: skips
 * any user whose email already exists, so dropping the volume gives you
 * the same accounts again, and starting an existing DB doesn't duplicate
 * anything.
 *
 * <p>Each seeded user also gets their starter catalog. For the basic
 * test user we additionally seed two clients, one project and one
 * estimate with four line items, so the admin dashboard has numbers
 * and Swagger has data to play with from the first request.</p>
 */
@Slf4j
@Component
@Profile("dev")
@RequiredArgsConstructor
public class DevDataSeeder implements ApplicationRunner {

    private static final String DEV_PHONE = "+380501112233";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final CatalogTemplateService catalogTemplateService;
    private final ClientRepository clientRepository;
    private final ProjectRepository projectRepository;
    private final EstimateRepository estimateRepository;
    private final EstimateItemRepository estimateItemRepository;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        Optional<User> test  = seedUser("test@majstr.dev",  "Test1234!",  "Тест Користувач",      Set.of(Trade.ELECTRICAL),                Plan.FREE, Role.USER);
        seedUser("pro@majstr.dev",   "Pro1234!",   "Pro Підрядник",        Set.of(Trade.PLUMBING),                  Plan.PRO,  Role.USER);
        // Admin is a generalist with two trades — exercises the merged starter catalog.
        seedUser("admin@majstr.dev", "Admin1234!", "Адміністратор Majstr", Set.of(Trade.GENERAL, Trade.ELECTRICAL), Plan.TEAM, Role.ADMIN);

        // Demo content only for a freshly created basic user — restart of
        // an existing DB will not duplicate it. Delete the test user (or
        // its project) by hand if you want a clean slate.
        test.ifPresent(this::seedDemoContentFor);
    }

    private Optional<User> seedUser(String email, String password, String fullName, Set<Trade> trades, Plan plan, Role role) {
        if (userRepository.existsByEmailIgnoreCase(email)) {
            return Optional.empty();
        }
        User user = User.builder()
                .email(email)
                .passwordHash(passwordEncoder.encode(password))
                .fullName(fullName)
                .trades(new LinkedHashSet<>(trades))
                .phone(DEV_PHONE)
                .companyName(fullName + " ФОП")
                .plan(plan)
                .role(role)
                .build();
        user = userRepository.save(user);
        int templateCount = catalogTemplateService.seedForUser(user);
        log.info("Dev seed: created {} (plan={}, role={}, trades={}) with {} catalog items — password '{}'",
                email, plan, role, trades, templateCount, password);
        return Optional.of(user);
    }

    private void seedDemoContentFor(User owner) {
        Client client1 = clientRepository.save(Client.builder()
                .owner(owner)
                .fullName("Олена Іваненко")
                .phone("+380671234567")
                .address("Київ, вул. Хрещатик 1")
                .build());
        clientRepository.save(Client.builder()
                .owner(owner)
                .fullName("Сергій Коваль")
                .phone("+380502223344")
                .address("Львів, вул. Бандери 17")
                .build());

        Project project = projectRepository.save(Project.builder()
                .owner(owner)
                .client(client1)
                .name("Квартира на Хрещатику")
                .address("вул. Хрещатик 1, кв. 25, Київ")
                .description("Електромонтаж під ключ: щиток, розетки, освітлення")
                .status(ProjectStatus.ESTIMATING)
                .build());

        Estimate estimate = estimateRepository.save(Estimate.builder()
                .project(project)
                .status(EstimateStatus.DRAFT)
                .validUntil(LocalDate.now().plusDays(30))
                .notes("Передоплата 30%. Гарантія на роботи — 12 місяців. Матеріали — за чеком.")
                .build());

        // Four representative line items, picked to roughly match the
        // ELECTRICAL templates so prices and units look natural.
        addItem(estimate, ItemType.WORK,     "Прокладка кабелю в гофрі", Unit.M,     "35.000", "45.00",  1);
        addItem(estimate, ItemType.WORK,     "Встановлення розетки",     Unit.PIECE, "12.000", "180.00", 2);
        addItem(estimate, ItemType.MATERIAL, "Кабель ВВГнг 3х2.5",       Unit.M,     "40.000", "38.50",  3);
        addItem(estimate, ItemType.MATERIAL, "Розетка Schneider Asfora", Unit.PIECE, "12.000", "95.00",  4);

        log.info("Dev seed: created demo content for {} — 2 clients, 1 project, 1 estimate with 4 items",
                owner.getEmail());
    }

    private void addItem(Estimate estimate, ItemType type, String name, Unit unit,
                         String quantity, String unitPrice, int sortOrder) {
        estimateItemRepository.save(EstimateItem.builder()
                .estimate(estimate)
                .type(type)
                .name(name)
                .unit(unit)
                .quantity(new BigDecimal(quantity))
                .unitPrice(new BigDecimal(unitPrice))
                .sortOrder(sortOrder)
                .build());
    }
}
