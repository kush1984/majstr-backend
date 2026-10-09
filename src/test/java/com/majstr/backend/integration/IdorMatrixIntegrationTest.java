package com.majstr.backend.integration;

import com.majstr.backend.entity.Plan;
import com.majstr.backend.entity.User;
import com.majstr.backend.repository.UserRepository;
import com.majstr.backend.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The IDOR matrix: two real masters, and every owner-scoped {@code /api} route called with the
 * OTHER one's ids, over the real filter chain and a real socket.
 *
 * <p><b>Why it exists.</b> Ownership is the product's only tenancy boundary, and until this test
 * nothing exercised it end to end. Every controller test is standalone MockMvc — no Spring Security,
 * no database — asserting that the controller hands {@code principal.id()} to a MOCKED service, so a
 * service that loads by id alone ships with the whole suite green.
 * {@code SecurityMatrixIntegrationTest} covers the gate ("was I let in"); this covers the row
 * ("whose row did I get").</p>
 *
 * <p><b>Three attack classes, because the second is the one that gets missed.</b></p>
 * <ol>
 *   <li><b>Foreign parent</b> — B calls with A's parent id ({@code /api/projects/<A>/notes}). Any
 *       ownership check at all catches this.</li>
 *   <li><b>Foreign child under B's OWN parent</b> — B passes HIS project and A's note id
 *       ({@code /api/projects/<B>/notes/<A's note>}). The parent check passes, so the child must
 *       still be looked up BY PARENT and not by id alone. This is the class a
 *       {@code findById} + {@code requireOwner(parent)} shape lets straight through.</li>
 *   <li><b>A foreign id in the BODY</b> — path and parent are entirely B's own and the foreign id
 *       rides a request field. A status proves nothing here (ignoring the id is a correct answer),
 *       so these assert A's row is unchanged afterwards.</li>
 * </ol>
 *
 * <p><b>404, not 403.</b> A foreign id should be indistinguishable from one that never existed —
 * 403 answers "it exists and is not yours", which is itself a disclosure (review round 2 settled
 * exactly this for a foreign cash id). Both are accepted because a couple of routes legitimately
 * answer 403 from a gate ahead of the lookup, but 2xx fails, and so do <b>400 and 5xx</b>: a 400
 * means the body never reached the ownership check, which would make the case vacuous, and a 5xx is
 * a crash on untrusted input. A matrix that 400s everywhere is a matrix that tests nothing.</p>
 *
 * <p><b>Both masters are TEAM, and both are email-verified.</b> Every feature gate must be OPEN, or
 * a 403 from {@code PlanConfig} (or from the share endpoint's {@code EMAIL_NOT_VERIFIED}) would
 * stand in for an ownership check that was never reached.</p>
 *
 * <p><b>The coverage guard is the load-bearing part.</b> A hand-written case table rots the moment
 * someone adds an endpoint, so {@link #everyOwnerScopedRouteHasACase} enumerates
 * {@link RequestMappingHandlerMapping} at runtime and fails for any id-bearing {@code /api} route
 * that is neither in the table nor in {@link #EXEMPT} with a reason. It checks the other direction
 * too — a case naming a route that does not exist would 404 for the wrong reason and pass quietly
 * forever.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class IdorMatrixIntegrationTest extends IntegrationTestBase {

    /** What a foreign id must answer. 404 is the right one; 403 is tolerated (see class javadoc). */
    private static final Set<Integer> DENIED = Set.of(403, 404);

    private static final String JSON = "application/json";
    private static final String BOUNDARY = "----MajstrIdorBoundary";
    private static final String MULTIPART = "multipart/form-data; boundary=" + BOUNDARY;

    /**
     * Routes carrying a path variable that is not an owned id. Empty on purpose — every id-bearing
     * {@code /api} route today is owner-scoped. ({@code GET /api/files/**} is authorised by the
     * storage key itself and carries no path variable, so the filter below never reaches it.)
     */
    private static final Set<String> EXEMPT = Set.of();

    // ---- bodies ----------------------------------------------------------------
    // Each is the MINIMUM that passes bean validation, because the assertion below refuses 400:
    // a body that fails validation never reaches the ownership check it is meant to probe.

    private static final String B_CLIENT = "{\"fullName\":\"Клієнт\",\"phone\":\"+380991112233\"}";
    private static final String B_PROJECT = "{\"name\":\"Обʼєкт\",\"address\":\"вул. Тестова 1\"}";
    private static final String B_PROJECT_STATUS = "{\"status\":\"IN_PROGRESS\"}";
    private static final String B_ESTIMATE_CREATE = "{\"name\":\"Кошторис\"}";
    private static final String B_ESTIMATE_UPDATE = "{\"status\":\"DRAFT\"}";
    private static final String B_COUNT_IN_ECONOMY = "{\"countInEconomy\":true}";
    private static final String B_ESTIMATE_ITEM =
            "{\"name\":\"Робота\",\"type\":\"WORK\",\"unit\":\"M2\",\"quantity\":1,\"unitPrice\":100}";
    private static final String B_ITEM_FROM_CATALOG = "{\"quantity\":1}";
    private static final String B_DUPLICATE = "{\"discount\":false,\"markupPercent\":0}";
    private static final String B_SAVE_AS_TEMPLATE = "{\"name\":\"Набір\"}";
    private static final String B_TEMPLATE_TRADE = "{\"trade\":\"PAINTER\"}";
    private static final String B_TEMPLATE_ITEM = "{\"name\":\"Позиція\",\"type\":\"WORK\",\"unit\":\"M2\"}";
    private static final String B_MATERIAL_PARAMS = "{\"perimeter\":10}";
    private static final String B_MATERIAL_NORM = "{\"qtyPerUnit\":1.5}";
    private static final String B_ROOM = "{\"name\":\"Кімната\"}";
    private static final String B_MEASUREMENT_ITEM =
            "{\"name\":\"Стіна\",\"type\":\"SURFACE\",\"payload\":{\"width\":3,\"height\":2.7}}";
    private static final String B_EXPENSE = "{\"amount\":100,\"category\":\"MATERIALS\"}";
    private static final String B_PAYMENT = "{\"amount\":1000,\"purpose\":\"Аванс\"}";
    private static final String B_PAYMENT_RECEIPT = "{\"amount\":100,\"receivedAt\":\"2026-01-01\"}";
    private static final String B_PAYMENT_SPLIT = "{\"preset\":\"FIFTY_FIFTY\"}";
    private static final String B_CUSTOM_TRADE = "{\"name\":\"Свій трейд\"}";
    private static final String B_NOTE = "{\"body\":\"Нотатка\"}";
    private static final String B_PHOTO_FOLDER = "{\"folder\":\"Тека\"}";
    private static final String B_PHOTO_VISIBILITY = "{\"visibility\":\"SHARED\"}";
    private static final String B_PORTAL = "{\"estimateIds\":[]}";
    private static final String B_ECONOMY = "{\"paymentsVisible\":false}";
    private static final String B_PROJECT_RECEIPT = "{\"label\":\"Чек\",\"amount\":100}";
    private static final String B_SHOPPING_ITEM = "{\"name\":\"Клей\",\"quantity\":2,\"unit\":\"PIECE\"}";
    private static final String B_SHOPPING_ITEM_UPDATE = "{\"quantity\":3}";
    private static final String B_ACT_CREATE = "{\"issuedAt\":\"2026-01-10\",\"kind\":\"INTERIM\","
            + "\"periodFrom\":\"2026-01-01\",\"periodTo\":\"2026-01-09\"}";
    private static final String B_ACT_ITEMS = "{\"items\":[{\"name\":\"Робота\",\"type\":\"WORK\","
            + "\"unit\":\"M2\",\"quantity\":1,\"unitPrice\":100}]}";
    /** Same required set as the create body — a header update restates the whole header. */
    private static final String B_ACT_UPDATE = B_ACT_CREATE;
    private static final String B_ACT_STATUS = "{\"status\":\"DRAFT\"}";
    private static final String B_ACT_SIGN_OFFLINE = "{\"signerName\":\"Клієнт\"}";
    private static final String B_ACT_RECEIPT = "{\"label\":\"Чек\",\"amount\":100}";
    private static final String B_FISCAL_QR = "{\"payload\":\"not-a-real-qr\"}";
    private static final String B_DICTATION_PARSE = "{\"text\":\"штукатурка стін 10 метрів\"}";
    private static final String B_COMMIT_ITEMS = "{\"items\":[{\"name\":\"Матеріал\",\"type\":\"MATERIAL\","
            + "\"unit\":\"PIECE\",\"quantity\":1,\"unitPrice\":50}]}";
    private static final String B_CATALOG_ITEM =
            "{\"name\":\"Позиція каталогу\",\"type\":\"WORK\",\"unit\":\"M2\",\"defaultPrice\":150}";
    // `materialRefund` is a required wrapper since review B-104 — without it the PATCH is a 400 and
    // never reaches the ownership check.
    private static final String B_CASH = "{\"amount\":500,\"direction\":\"INCOME\",\"materialRefund\":false}";
    // `rooms[].items` is @NotEmpty, so an empty array answers 400 and the case proves nothing
    // about ownership.
    private static final String B_ROOMS_COMMIT =
            "{\"rooms\":[{\"name\":\"Кімната\",\"items\":[{\"name\":\"Стіна\","
            + "\"type\":\"SURFACE\",\"payload\":{\"segments\":[{\"l\":4,\"w\":2.7}],"
            + "\"openings\":[]}}]}]}";
    private static final String B_TRIAGE = "{\"sheets\":[{\"name\":\"Аркуш 1\",\"text\":\"план\"}]}";

    @Autowired Environment env;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserRepository userRepository;
    @Autowired JwtService jwtService;

    @Autowired @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping handlerMapping;

    private final HttpClient http = HttpClient.newHttpClient();

    /** Seeded once for the class: two masters, each with a complete data graph. */
    private static Master victim;
    private static Master attacker;

    @BeforeEach
    void seedBothMasters() {
        if (victim == null) {
            victim = seed("A");
            attacker = seed("B");
        }
    }

    // ---- the matrix ------------------------------------------------------------

    @Test
    void aForeignParentIdIsNeverServed() throws Exception {
        assertDenied(foreignParentCases());
    }

    @Test
    void aForeignChildUnderMyOwnParentIsNeverServed() throws Exception {
        assertDenied(foreignChildCases());
    }

    @Test
    void aForeignIdInTheBodyChangesNothingOfMine() throws Exception {
        // The path here is entirely the attacker's own, so a 2xx is a legitimate answer — what must
        // not happen is the victim's row moving. Each case therefore carries a fingerprint of the
        // row it targets, read before and after.
        List<String> leaks = new ArrayList<>();
        for (BodyCase c : bodyBorneCases()) {
            String before = jdbc.queryForObject(c.fingerprintSql(), String.class, c.fingerprintArg());
            Hit hit = send(c.request(), attacker.token());
            String after = jdbc.queryForObject(c.fingerprintSql(), String.class, c.fingerprintArg());
            if (!before.equals(after)) {
                leaks.add("%s %s moved %s: %s -> %s".formatted(
                        c.request().method(), c.request().path(), c.what(), before, after));
            }
            if (hit.status() >= 500) {
                leaks.add("%s %s answered %d — %s".formatted(
                        c.request().method(), c.request().path(), hit.status(), snippet(hit.body())));
            }
        }
        assertThat(leaks).as("a foreign id in a request body reached the victim's row").isEmpty();
    }

    @Test
    void everyOwnerScopedRouteHasACase() {
        Set<String> covered = new LinkedHashSet<>();
        foreignParentCases().forEach(c -> covered.add(c.method() + " " + c.template()));
        foreignChildCases().forEach(c -> covered.add(c.method() + " " + c.template()));
        bodyBorneCases().forEach(c -> covered.add(c.request().method() + " " + c.request().template()));

        Set<String> registered = new LinkedHashSet<>();
        Set<String> ownerScoped = new LinkedHashSet<>();
        handlerMapping.getHandlerMethods().forEach((info, handler) -> {
            Set<String> methods = new LinkedHashSet<>();
            info.getMethodsCondition().getMethods().forEach(m -> methods.add(m.name()));
            if (methods.isEmpty()) {
                methods.add("GET");
            }
            for (String pattern : info.getPatternValues()) {
                for (String method : methods) {
                    registered.add(method + " " + pattern);
                    if (isOwnerScopedIdBearing(pattern)) {
                        ownerScoped.add(method + " " + pattern
                                + "  (" + handler.getBeanType().getSimpleName() + ")");
                    }
                }
            }
        });

        List<String> uncovered = ownerScoped.stream()
                .filter(row -> {
                    String key = row.substring(0, row.indexOf("  ("));
                    return !covered.contains(key) && !EXEMPT.contains(key);
                })
                .toList();
        assertThat(uncovered)
                .as("""
                        every id-bearing owner-scoped /api route needs an IDOR case. Add one to \
                        foreignParentCases()/foreignChildCases(), or to EXEMPT with the reason its \
                        path variable is not an owned id.""")
                .isEmpty();

        // The other direction: a case naming a route that does not exist would 404 for the wrong
        // reason and keep passing after the endpoint it meant to cover was renamed away.
        List<String> phantom = covered.stream().filter(key -> !registered.contains(key)).toList();
        assertThat(phantom)
                .as("these cases name a route this application does not serve")
                .isEmpty();
    }

    /**
     * {@code /api/public/**} is token-authorised and {@code /api/admin/**} role-authorised (both
     * covered elsewhere), {@code /api/auth/**} is pre-authentication and {@code /api/billing/**} is
     * keyed by the caller's own subscription. Everything else under {@code /api} carrying a path
     * variable is owner-scoped by definition.
     */
    private static boolean isOwnerScopedIdBearing(String pattern) {
        return pattern.startsWith("/api/")
                && !pattern.startsWith("/api/public/")
                && !pattern.startsWith("/api/admin/")
                && !pattern.startsWith("/api/auth/")
                && !pattern.startsWith("/api/billing/")
                && pattern.contains("{");
    }

    private static final String[] INBOX_BASES = {
            "/api/projects/{projectId}/messages",
            "/api/projects/{projectId}/questions",
    };

    // ---- class 1: B calls with A's parent id -----------------------------------

    private List<Case> foreignParentCases() {
        Ids a = victim.ids();
        List<Case> c = new ArrayList<>();

        // --- clients, objects ---------------------------------------------------
        c.add(get("/api/clients/{id}", a.client));
        c.add(json("PUT", "/api/clients/{id}", B_CLIENT, a.client));
        c.add(del("/api/clients/{id}", a.client));
        c.add(get("/api/projects/{id}", a.project));
        c.add(json("PUT", "/api/projects/{id}", B_PROJECT, a.project));
        c.add(json("PATCH", "/api/projects/{id}/status", B_PROJECT_STATUS, a.project));
        c.add(del("/api/projects/{id}", a.project));

        // --- the catalog and its notices ----------------------------------------
        c.add(json("PUT", "/api/catalog/{id}", B_CATALOG_ITEM, a.catalogItem));
        c.add(del("/api/catalog/{id}", a.catalogItem));
        // Both go through `findByIdAndUserId`, so a foreign notice is a no-op rather than a
        // refusal — the witness is what proves the victim's notice is untouched.
        c.add(watching(post("/api/catalog/update-notice/{id}/dismiss", a.notice),
                "catalog_update_notices", a.notice));
        c.add(watching(post("/api/catalog/update-notice/{id}/accept", a.notice),
                "catalog_update_notices", a.notice));

        // --- «Мої гроші», custom trades, norms ----------------------------------
        c.add(json("PATCH", "/api/cash/{id}", B_CASH, a.cash));
        // No `kind` means PERSONAL, which is `findByIdAndOwnerId` — a no-op, and B-45 settled
        // that it answers 404 rather than 403 for a row that IS someone's. The three object kinds
        // take a different branch, which really does run `requireOwner`.
        c.add(watching(del("/api/cash/{id}", a.cash), "cash_entry", a.cash));
        c.add(withQuery(del("/api/cash/{id}", a.paymentReceipt), "kind=OBJECT_PAYMENT"));
        c.add(withQuery(del("/api/cash/{id}", a.expense), "kind=OBJECT_EXPENSE"));
        c.add(withQuery(del("/api/cash/{id}", a.projectReceipt), "kind=OBJECT_RECEIPT"));
        c.add(json("PATCH", "/api/profile/custom-trades/{id}", B_CUSTOM_TRADE, a.customTrade));
        c.add(watching(del("/api/profile/custom-trades/{id}", a.customTrade),
                "user_trade", a.customTrade));
        c.add(json("PUT", "/api/material-norms/{normId}", B_MATERIAL_NORM, a.norm));
        c.add(del("/api/material-norms/{normId}", a.norm));

        // --- estimates under a foreign object -----------------------------------
        c.add(get("/api/projects/{projectId}/estimates", a.project));
        c.add(json("POST", "/api/projects/{projectId}/estimates", B_ESTIMATE_CREATE, a.project));
        c.add(json("POST", "/api/projects/{projectId}/estimates/consolidate",
                "{\"estimateIds\":[\"" + a.estimate + "\"]}", a.project));
        c.add(json("POST", "/api/projects/{projectId}/estimates/from-template/{templateId}",
                B_ESTIMATE_CREATE, a.project, a.template));
        c.add(json("POST", "/api/projects/{projectId}/estimates/from-templates",
                "{\"templates\":[{\"templateId\":\"" + a.template + "\"}]}", a.project));

        // --- a foreign estimate -------------------------------------------------
        c.add(get("/api/estimates/{id}", a.estimate));
        c.add(json("PUT", "/api/estimates/{id}", B_ESTIMATE_UPDATE, a.estimate));
        c.add(del("/api/estimates/{id}", a.estimate));
        c.add(post("/api/estimates/{id}/reopen", a.estimate));
        c.add(json("PATCH", "/api/estimates/{id}/count-in-economy", B_COUNT_IN_ECONOMY, a.estimate));
        c.add(post("/api/estimates/{id}/dismiss-superseded", a.estimate));
        c.add(get("/api/estimates/{id}/pdf", a.estimate));
        c.add(post("/api/estimates/{id}/share", a.estimate));
        c.add(post("/api/estimates/{id}/share/send-email", a.estimate));
        c.add(del("/api/estimates/{id}/share/{linkId}", a.estimate, a.estimateShareLink));
        c.add(json("POST", "/api/estimates/{id}/save-as-template", B_SAVE_AS_TEMPLATE, a.estimate));
        c.add(json("POST", "/api/estimates/{estimateId}/duplicate", B_DUPLICATE, a.estimate));
        c.add(json("POST", "/api/estimates/{estimateId}/items", B_ESTIMATE_ITEM, a.estimate));
        c.add(json("POST", "/api/estimates/{estimateId}/items/from-catalog/{catalogItemId}",
                B_ITEM_FROM_CATALOG, a.estimate, a.catalogItem));
        c.add(json("POST", "/api/estimates/{estimateId}/items/batch",
                "{\"items\":[{\"catalogItemId\":\"" + a.catalogItem + "\",\"quantity\":1}]}", a.estimate));
        c.add(json("PUT", "/api/estimates/{estimateId}/items/{itemId}",
                B_ESTIMATE_ITEM, a.estimate, a.estimateItem));
        c.add(json("PUT", "/api/estimates/{estimateId}/items/order",
                "{\"items\":[{\"id\":\"" + a.estimateItem + "\"}]}", a.estimate));
        c.add(del("/api/estimates/{estimateId}/items/{itemId}", a.estimate, a.estimateItem));
        c.add(json("POST", "/api/estimates/{estimateId}/items/delete",
                "{\"itemIds\":[\"" + a.estimateItem + "\"]}", a.estimate));
        c.add(json("POST", "/api/estimates/{estimateId}/items/markup",
                "{\"discount\":false,\"percent\":10,\"itemIds\":[\"" + a.estimateItem + "\"]}",
                a.estimate));

        // --- a foreign bundle ---------------------------------------------------
        c.add(get("/api/estimate-templates/{id}", a.template));
        c.add(json("PATCH", "/api/estimate-templates/{id}", B_SAVE_AS_TEMPLATE, a.template));
        c.add(json("PATCH", "/api/estimate-templates/{id}/trade", B_TEMPLATE_TRADE, a.template));
        c.add(del("/api/estimate-templates/{id}", a.template));
        c.add(json("POST", "/api/estimate-templates/{id}/items", B_TEMPLATE_ITEM, a.template));
        c.add(json("PATCH", "/api/estimate-templates/{id}/items/{itemId}",
                B_TEMPLATE_ITEM, a.template, a.templateItem));
        c.add(del("/api/estimate-templates/{id}/items/{itemId}", a.template, a.templateItem));
        c.add(json("PUT", "/api/estimate-templates/{id}/items/order",
                "{\"itemIds\":[\"" + a.templateItem + "\"]}", a.template));

        // --- the calculator -----------------------------------------------------
        c.add(get("/api/estimates/{estimateId}/materials", a.estimate));
        c.add(get("/api/estimates/{estimateId}/materials/availability", a.estimate));
        c.add(json("PUT", "/api/estimates/{estimateId}/materials/params",
                B_MATERIAL_PARAMS, a.estimate));
        c.add(json("POST", "/api/estimates/{estimateId}/materials/shopping-list",
                "{\"materials\":[{\"materialId\":\"" + a.material + "\",\"quantity\":1}]}", a.estimate));

        // --- dictation and the receipt-items import on a foreign estimate --------
        c.add(json("POST", "/api/estimates/{id}/dictation/parse", B_DICTATION_PARSE, a.estimate));
        c.add(json("POST", "/api/estimates/{id}/dictation/commit", B_COMMIT_ITEMS, a.estimate));
        c.add(json("POST", "/api/estimates/{id}/receipt-items/qr", B_FISCAL_QR, a.estimate));
        c.add(json("POST", "/api/estimates/{id}/receipt-items/commit", B_COMMIT_ITEMS, a.estimate));
        c.add(upload("POST", "/api/estimates/{id}/receipt-items/parse", a.estimate));

        // --- measurements -------------------------------------------------------
        c.add(get("/api/projects/{id}/measurements", a.project));
        c.add(json("POST", "/api/projects/{id}/measurements/rooms", B_ROOM, a.project));
        c.add(json("PATCH", "/api/projects/{id}/measurements/rooms/{roomId}",
                B_ROOM, a.project, a.room));
        c.add(del("/api/projects/{id}/measurements/rooms/{roomId}", a.project, a.room));
        c.add(json("POST", "/api/projects/{id}/measurements/rooms/{roomId}/items",
                B_MEASUREMENT_ITEM, a.project, a.room));
        c.add(json("PATCH", "/api/projects/{id}/measurements/rooms/{roomId}/items/{itemId}",
                B_MEASUREMENT_ITEM, a.project, a.room, a.measurementItem));
        c.add(del("/api/projects/{id}/measurements/rooms/{roomId}/items/{itemId}",
                a.project, a.room, a.measurementItem));
        c.add(upload("POST", "/api/projects/{id}/measurements/sketch/parse", a.project));
        c.add(json("POST", "/api/projects/{id}/measurements/sketch/commit",
                B_ROOMS_COMMIT, a.project));
        c.add(json("POST", "/api/projects/{projectId}/measurements/project/triage",
                B_TRIAGE, a.project));
        c.add(uploadWith("POST", "/api/projects/{projectId}/measurements/project/parse",
                "kind", "PLAN", a.project));
        c.add(json("POST", "/api/projects/{projectId}/measurements/project/commit",
                B_ROOMS_COMMIT, a.project));
        c.add(upload("POST", "/api/projects/{id}/measurements/electrical/plan/parse", a.project));

        // --- economy, expenses, payments ----------------------------------------
        c.add(get("/api/projects/{id}/economy", a.project));
        c.add(get("/api/projects/{id}/expenses", a.project));
        c.add(json("POST", "/api/projects/{id}/expenses", B_EXPENSE, a.project));
        c.add(json("PATCH", "/api/projects/{id}/expenses/{expenseId}",
                B_EXPENSE, a.project, a.expense));
        c.add(del("/api/projects/{id}/expenses/{expenseId}", a.project, a.expense));
        c.add(get("/api/projects/{id}/payments", a.project));
        c.add(get("/api/projects/{id}/payments/summary", a.project));
        c.add(json("POST", "/api/projects/{id}/payments", B_PAYMENT, a.project));
        c.add(json("PATCH", "/api/projects/{id}/payments/{paymentId}",
                B_PAYMENT, a.project, a.payment));
        c.add(del("/api/projects/{id}/payments/{paymentId}", a.project, a.payment));
        c.add(json("POST", "/api/projects/{id}/payments/split/preview", B_PAYMENT_SPLIT, a.project));
        c.add(json("POST", "/api/projects/{id}/payments/split/commit", B_PAYMENT_SPLIT, a.project));
        c.add(json("POST", "/api/projects/{id}/payments/receipts", B_PAYMENT_RECEIPT, a.project));
        c.add(json("PATCH", "/api/projects/{id}/payments/receipts/{receiptId}",
                B_PAYMENT_RECEIPT, a.project, a.paymentReceipt));
        c.add(del("/api/projects/{id}/payments/receipts/{receiptId}", a.project, a.paymentReceipt));
        c.add(json("POST", "/api/projects/{id}/payments/receipts/transfer-surplus",
                "{\"fromPaymentId\":\"" + a.payment + "\",\"toPaymentId\":\"" + a.payment + "\"}",
                a.project));

        // --- notes --------------------------------------------------------------
        c.add(get("/api/projects/{id}/notes", a.project));
        c.add(json("POST", "/api/projects/{id}/notes", B_NOTE, a.project));
        c.add(json("PATCH", "/api/projects/{id}/notes/{noteId}", B_NOTE, a.project, a.note));
        c.add(del("/api/projects/{id}/notes/{noteId}", a.project, a.note));

        // --- photos -------------------------------------------------------------
        c.add(get("/api/projects/{projectId}/photos", a.project));
        c.add(upload("POST", "/api/projects/{projectId}/photos", a.project));
        c.add(json("PATCH", "/api/projects/{projectId}/photos/{photoId}",
                B_PHOTO_VISIBILITY, a.project, a.photo));
        c.add(json("PATCH", "/api/projects/{projectId}/photos/{photoId}/folder",
                B_PHOTO_FOLDER, a.project, a.photo));
        c.add(del("/api/projects/{projectId}/photos/{photoId}", a.project, a.photo));
        c.add(get("/api/projects/{projectId}/photos/{photoId}/file", a.project, a.photo));
        c.add(get("/api/projects/{projectId}/photos/folders", a.project));
        c.add(json("POST", "/api/projects/{projectId}/photos/folders", B_PHOTO_FOLDER, a.project));
        c.add(del("/api/projects/{projectId}/photos/folders/{folderId}", a.project, a.photoFolder));

        // --- the client portal and the message link -----------------------------
        c.add(get("/api/projects/{projectId}/portal", a.project));
        c.add(json("PUT", "/api/projects/{projectId}/portal", B_PORTAL, a.project));
        c.add(post("/api/projects/{projectId}/portal/send-email", a.project));
        c.add(get("/api/projects/{projectId}/portal/economy", a.project));
        c.add(json("PUT", "/api/projects/{projectId}/portal/economy", B_ECONOMY, a.project));
        c.add(del("/api/projects/{projectId}/portal/economy", a.project));
        c.add(post("/api/projects/{projectId}/portal/economy/send-email", a.project));
        c.add(get("/api/projects/{projectId}/message-link", a.project));
        c.add(del("/api/projects/{projectId}/message-link", a.project));

        // --- the inbox, under BOTH of its paths (the /questions alias is live) ---
        for (String base : INBOX_BASES) {
            c.add(get(base, a.project));
            c.add(patchNoBody(base + "/{questionId}/read", a.project, a.message));
            c.add(del(base + "/{messageId}", a.project, a.message));
            c.add(get(base + "/{messageId}/files/{fileId}", a.project, a.message, a.messageFile));
        }

        // --- object receipts («Чеки обʼєкта») -----------------------------------
        c.add(get("/api/projects/{id}/receipts", a.project));
        c.add(upload("POST", "/api/projects/{id}/receipts", a.project));
        c.add(json("POST", "/api/projects/{id}/receipts/qr", B_FISCAL_QR, a.project));
        c.add(post("/api/projects/{id}/receipts/{receiptId}/recognize", a.project, a.projectReceipt));
        c.add(json("PATCH", "/api/projects/{id}/receipts/{receiptId}",
                B_PROJECT_RECEIPT, a.project, a.projectReceipt));
        c.add(del("/api/projects/{id}/receipts/{receiptId}", a.project, a.projectReceipt));
        c.add(get("/api/projects/{id}/receipts/{receiptId}/file", a.project, a.projectReceipt));

        // --- the shopping list --------------------------------------------------
        c.add(get("/api/projects/{id}/shopping-list", a.project));
        c.add(get("/api/projects/{id}/shopping-list/pdf", a.project));
        c.add(json("POST", "/api/projects/{id}/shopping-list/items", B_SHOPPING_ITEM, a.project));
        c.add(json("PATCH", "/api/projects/{id}/shopping-list/items/{itemId}",
                B_SHOPPING_ITEM_UPDATE, a.project, a.shoppingItem));
        c.add(del("/api/projects/{id}/shopping-list/items/{itemId}", a.project, a.shoppingItem));
        c.add(post("/api/projects/{id}/shopping-list/clear-bought", a.project));

        // --- work acts ----------------------------------------------------------
        c.add(get("/api/projects/{projectId}/acts", a.project));
        c.add(get("/api/projects/{projectId}/acts/progress", a.project));
        c.add(json("POST", "/api/projects/{projectId}/acts", B_ACT_CREATE, a.project));
        c.add(get("/api/acts/{id}", a.act));
        c.add(json("PATCH", "/api/acts/{id}", B_ACT_UPDATE, a.act));
        c.add(json("PUT", "/api/acts/{id}/items", B_ACT_ITEMS, a.act));
        c.add(del("/api/acts/{id}", a.act));
        c.add(json("POST", "/api/acts/{id}/sign-offline", B_ACT_SIGN_OFFLINE, a.act));
        c.add(json("PATCH", "/api/acts/{id}/status", B_ACT_STATUS, a.act));
        c.add(get("/api/acts/{id}/pdf", a.act));
        c.add(get("/api/acts/{id}/receipts", a.act));
        c.add(uploadWith("POST", "/api/acts/{id}/receipts", "amount", "100", a.act));
        c.add(upload("POST", "/api/acts/{id}/receipts/recognize", a.act));
        c.add(post("/api/acts/{id}/receipts/{receiptId}/recognize", a.act, a.actReceipt));
        c.add(json("POST", "/api/acts/{id}/receipts/qr", B_FISCAL_QR, a.act));
        c.add(json("PATCH", "/api/acts/{id}/receipts/{receiptId}",
                B_ACT_RECEIPT, a.act, a.actReceipt));
        c.add(del("/api/acts/{id}/receipts/{receiptId}", a.act, a.actReceipt));
        c.add(get("/api/acts/{id}/receipts/{receiptId}/file", a.act, a.actReceipt));
        c.add(get("/api/acts/{id}/share", a.act));
        c.add(putNoBody("/api/acts/{id}/share", a.act));
        c.add(post("/api/acts/{id}/share/send-email", a.act));

        return c;
    }

    // ---- class 2: B's own parent, A's child ------------------------------------
    // The shape a `findById(childId)` + `requireOwner(parent)` pair lets through: the parent check
    // passes because the parent really is B's, and the child was never asked whom it belongs to.

    private List<Case> foreignChildCases() {
        Ids a = victim.ids();
        Ids b = attacker.ids();
        List<Case> c = new ArrayList<>();

        // --- estimate lines, and a catalog row read by id -----------------------
        c.add(json("PUT", "/api/estimates/{estimateId}/items/{itemId}",
                B_ESTIMATE_ITEM, b.estimate, a.estimateItem));
        // `deleteItems` filters by estimateId and documents itself «Idempotent by construction».
        c.add(watching(del("/api/estimates/{estimateId}/items/{itemId}", b.estimate, a.estimateItem),
                "estimate_items", a.estimateItem));
        c.add(json("POST", "/api/estimates/{estimateId}/items/from-catalog/{catalogItemId}",
                B_ITEM_FROM_CATALOG, b.estimate, a.catalogItem));
        c.add(json("POST", "/api/estimates/{estimateId}/items/batch",
                "{\"items\":[{\"catalogItemId\":\"" + a.catalogItem + "\",\"quantity\":1}]}",
                b.estimate));
        c.add(del("/api/estimates/{id}/share/{linkId}", b.estimate, a.estimateShareLink));

        // --- bundles ------------------------------------------------------------
        c.add(watching(json("PATCH", "/api/estimate-templates/{id}/items/{itemId}",
                B_TEMPLATE_ITEM, b.template, a.templateItem),
                "estimate_template_items", a.templateItem));
        c.add(watching(del("/api/estimate-templates/{id}/items/{itemId}", b.template, a.templateItem),
                "estimate_template_items", a.templateItem));
        c.add(json("POST", "/api/projects/{projectId}/estimates/from-template/{templateId}",
                B_ESTIMATE_CREATE, b.project, a.template));

        // --- measurements -------------------------------------------------------
        c.add(json("PATCH", "/api/projects/{id}/measurements/rooms/{roomId}",
                B_ROOM, b.project, a.room));
        c.add(watching(del("/api/projects/{id}/measurements/rooms/{roomId}", b.project, a.room),
                "measurement_room", a.room));
        c.add(json("POST", "/api/projects/{id}/measurements/rooms/{roomId}/items",
                B_MEASUREMENT_ITEM, b.project, a.room));
        c.add(json("PATCH", "/api/projects/{id}/measurements/rooms/{roomId}/items/{itemId}",
                B_MEASUREMENT_ITEM, b.project, b.room, a.measurementItem));
        c.add(watching(del("/api/projects/{id}/measurements/rooms/{roomId}/items/{itemId}",
                b.project, b.room, a.measurementItem), "measurement_item", a.measurementItem));

        // --- money under B's own object -----------------------------------------
        c.add(json("PATCH", "/api/projects/{id}/expenses/{expenseId}",
                B_EXPENSE, b.project, a.expense));
        c.add(watching(del("/api/projects/{id}/expenses/{expenseId}", b.project, a.expense),
                "object_expenses", a.expense));
        c.add(json("PATCH", "/api/projects/{id}/payments/{paymentId}",
                B_PAYMENT, b.project, a.payment));
        c.add(watching(del("/api/projects/{id}/payments/{paymentId}", b.project, a.payment),
                "project_payment", a.payment));
        c.add(json("PATCH", "/api/projects/{id}/payments/receipts/{receiptId}",
                B_PAYMENT_RECEIPT, b.project, a.paymentReceipt));
        c.add(watching(del("/api/projects/{id}/payments/receipts/{receiptId}", b.project, a.paymentReceipt),
                "payment_receipt", a.paymentReceipt));

        // --- notes, photos ------------------------------------------------------
        c.add(json("PATCH", "/api/projects/{id}/notes/{noteId}", B_NOTE, b.project, a.note));
        c.add(watching(del("/api/projects/{id}/notes/{noteId}", b.project, a.note),
                "project_note", a.note));
        c.add(json("PATCH", "/api/projects/{projectId}/photos/{photoId}",
                B_PHOTO_VISIBILITY, b.project, a.photo));
        c.add(json("PATCH", "/api/projects/{projectId}/photos/{photoId}/folder",
                B_PHOTO_FOLDER, b.project, a.photo));
        c.add(del("/api/projects/{projectId}/photos/{photoId}", b.project, a.photo));
        c.add(get("/api/projects/{projectId}/photos/{photoId}/file", b.project, a.photo));
        c.add(del("/api/projects/{projectId}/photos/folders/{folderId}", b.project, a.photoFolder));

        // --- the inbox, under both aliases --------------------------------------
        for (String base : INBOX_BASES) {
            c.add(patchNoBody(base + "/{questionId}/read", b.project, a.message));
            c.add(watching(del(base + "/{messageId}", b.project, a.message),
                    "project_messages", a.message));
            c.add(get(base + "/{messageId}/files/{fileId}", b.project, b.message, a.messageFile));
        }

        // --- object receipts, the shopping list ---------------------------------
        c.add(post("/api/projects/{id}/receipts/{receiptId}/recognize", b.project, a.projectReceipt));
        c.add(json("PATCH", "/api/projects/{id}/receipts/{receiptId}",
                B_PROJECT_RECEIPT, b.project, a.projectReceipt));
        c.add(del("/api/projects/{id}/receipts/{receiptId}", b.project, a.projectReceipt));
        c.add(get("/api/projects/{id}/receipts/{receiptId}/file", b.project, a.projectReceipt));
        c.add(json("PATCH", "/api/projects/{id}/shopping-list/items/{itemId}",
                B_SHOPPING_ITEM_UPDATE, b.project, a.shoppingItem));
        // A SETTLED row's delete HIDES it (`cleared_at`), which the whole-row fingerprint sees.
        c.add(watching(del("/api/projects/{id}/shopping-list/items/{itemId}", b.project, a.shoppingItem),
                "shopping_list_item", a.shoppingItem));

        // --- act receipts under B's own act -------------------------------------
        c.add(post("/api/acts/{id}/receipts/{receiptId}/recognize", b.act, a.actReceipt));
        c.add(json("PATCH", "/api/acts/{id}/receipts/{receiptId}",
                B_ACT_RECEIPT, b.act, a.actReceipt));
        c.add(del("/api/acts/{id}/receipts/{receiptId}", b.act, a.actReceipt));
        c.add(get("/api/acts/{id}/receipts/{receiptId}/file", b.act, a.actReceipt));

        // --- a foreign SOURCE in a body that COPIES ------------------------------
        // Both of these build something new out of what the body names, so letting a foreign id
        // through would copy A's lines into an estimate of B's — a read, not a write, which is why
        // they belong here and not with the fingerprinted cases.
        c.add(json("POST", "/api/projects/{projectId}/estimates/consolidate",
                "{\"estimateIds\":[\"" + a.estimate + "\"]}", b.project));
        c.add(json("POST", "/api/projects/{projectId}/estimates/from-templates",
                "{\"templates\":[{\"templateId\":\"" + a.template + "\"}]}", b.project));

        return c;
    }

    // ---- class 3: B's own path, A's id in the BODY ------------------------------
    // Here a 2xx is a legitimate answer — the request is about B's own object — so the status says
    // nothing. What must hold is that A's row is byte-identical afterwards, and that nothing of A's
    // got attached to anything of B's. Each case therefore reads a fingerprint before and after.

    private List<BodyCase> bodyBorneCases() {
        Ids a = victim.ids();
        Ids b = attacker.ids();
        List<BodyCase> c = new ArrayList<>();

        c.add(new BodyCase(
                json("POST", "/api/estimates/{estimateId}/items/markup",
                        "{\"discount\":false,\"percent\":50,\"itemIds\":[\"" + a.estimateItem + "\"]}",
                        b.estimate),
                "A's line price", LINE_SQL, a.estimateItem));
        c.add(new BodyCase(
                json("POST", "/api/estimates/{estimateId}/items/delete",
                        "{\"itemIds\":[\"" + a.estimateItem + "\"]}", b.estimate),
                "A's line", LINE_SQL, a.estimateItem));
        c.add(new BodyCase(
                json("PUT", "/api/estimates/{estimateId}/items/order",
                        "{\"items\":[{\"id\":\"" + a.estimateItem + "\"}]}", b.estimate),
                "A's line order", LINE_SQL, a.estimateItem));
        c.add(new BodyCase(
                json("PUT", "/api/estimate-templates/{id}/items/order",
                        "{\"itemIds\":[\"" + a.templateItem + "\"]}", b.template),
                "A's bundle position order", TEMPLATE_ITEM_SQL, a.templateItem));

        // A's estimate must not become visible on B's portal link. (The economy portal takes no
        // ids since B-103 — it shows B's own signed, counted estimates and nothing else.)
        c.add(new BodyCase(
                json("PUT", "/api/projects/{projectId}/portal",
                        "{\"estimateIds\":[\"" + a.estimate + "\"]}", b.project),
                "A's estimate visibility", ESTIMATE_SQL, a.estimate));

        // Money: a receipt filed against A's plan stage, and a surplus transferred out of it.
        c.add(new BodyCase(
                json("POST", "/api/projects/{id}/payments/receipts",
                        "{\"amount\":100,\"receivedAt\":\"2026-01-01\",\"planPaymentId\":\""
                                + a.payment + "\"}", b.project),
                "receipts filed against A's stage", RECEIPTS_OF_PAYMENT_SQL, a.payment));
        c.add(new BodyCase(
                json("POST", "/api/projects/{id}/payments/receipts/transfer-surplus",
                        "{\"fromPaymentId\":\"" + a.payment + "\",\"toPaymentId\":\""
                                + b.payment + "\"}", b.project),
                "receipts filed against A's stage", RECEIPTS_OF_PAYMENT_SQL, a.payment));

        // A's own trade name must not end up labelling B's bundle.
        c.add(new BodyCase(
                json("PATCH", "/api/estimate-templates/{id}/trade",
                        "{\"customTradeId\":\"" + a.customTrade + "\"}", b.template),
                "bundles pointing at A's trade", TEMPLATES_OF_TRADE_SQL, a.customTrade));

        // An object of B's claiming A's client.
        c.add(new BodyCase(
                json("POST", "/api/projects",
                        "{\"name\":\"Обʼєкт\",\"address\":\"вул. Тестова 2\",\"clientId\":\""
                                + a.client + "\"}"),
                "objects pointing at A's client", PROJECTS_OF_CLIENT_SQL, a.client));
        c.add(new BodyCase(
                json("PUT", "/api/projects/{id}",
                        "{\"name\":\"Обʼєкт\",\"address\":\"вул. Тестова 2\",\"clientId\":\""
                                + a.client + "\"}", b.project),
                "objects pointing at A's client", PROJECTS_OF_CLIENT_SQL, a.client));

        return c;
    }

    private static final String LINE_SQL = """
            select coalesce(max(unit_price::text || '|' || line_total::text || '|' || sort_order::text
                   || '|' || name), 'gone') from estimate_items where id = ?""";
    private static final String TEMPLATE_ITEM_SQL = """
            select coalesce(max(name || '|' || sort_order::text), 'gone')
            from estimate_template_items where id = ?""";
    private static final String ESTIMATE_SQL = """
            select coalesce(max(status || '|' || portal_visible::text
                   || '|' || count_in_economy::text || '|' || version::text), 'gone')
            from estimates where id = ?""";
    private static final String RECEIPTS_OF_PAYMENT_SQL =
            "select count(*)::text from payment_receipt where plan_payment_id = ?";
    private static final String TEMPLATES_OF_TRADE_SQL =
            "select count(*)::text from estimate_templates where custom_trade_id = ?";
    private static final String PROJECTS_OF_CLIENT_SQL =
            "select count(*)::text from projects where client_id = ?";

    // ---- the assertion ---------------------------------------------------------

    /**
     * Collects every non-conforming answer instead of failing on the first, so one run names the
     * whole set. {@code 400} fails on purpose: a request rejected by validation never reached the
     * ownership check, so the case proved nothing. A 2xx fails unless the case carries a
     * {@link Case#witnessSql() witness} AND the victim's row is unchanged — that is the one
     * legitimate 2xx, an idempotent delete that did nothing.
     */
    private void assertDenied(List<Case> cases) throws Exception {
        List<String> leaks = new ArrayList<>();
        for (Case c : cases) {
            String before = c.witnessSql() == null
                    ? null : jdbc.queryForObject(c.witnessSql(), String.class, c.witnessArg());
            Hit hit = send(c, attacker.token());
            if (DENIED.contains(hit.status())) {
                continue;
            }
            if (before != null && hit.status() < 300) {
                String after = jdbc.queryForObject(c.witnessSql(), String.class, c.witnessArg());
                if (before.equals(after)) {
                    continue;
                }
                leaks.add("%s %s -> %d AND TOUCHED the victim's row (%s -> %s)".formatted(
                        c.method(), c.path(), hit.status(), before, after));
                continue;
            }
            leaks.add("%s %s -> %d %s".formatted(
                    c.method(), c.path(), hit.status(), snippet(hit.body())));
        }
        assertThat(leaks)
                .as("""
                        every one of these answered something other than 403/404 for another \
                        master's id. A 2xx is a leak unless the case carries a witness and the \
                        victim's row is provably unchanged (a documented idempotent no-op); a 400 \
                        means the body never reached the ownership check (fix the body, the case \
                        is vacuous); a 5xx means untrusted input crashed the handler.""")
                .isEmpty();
    }

    private Hit send(Case c, String token) throws Exception {
        HttpRequest.BodyPublisher body = c.body() == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofByteArray(c.body());
        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port() + c.path()))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + token)
                .method(c.method(), body);
        if (c.contentType() != null) {
            b.header("Content-Type", c.contentType());
        }
        HttpResponse<String> res = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        return new Hit(res.statusCode(), res.body());
    }

    private static String snippet(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        String flat = body.replaceAll("\s+", " ").trim();
        return flat.length() <= 160 ? flat : flat.substring(0, 160) + "…";
    }

    private int port() {
        return Integer.parseInt(Objects.requireNonNull(env.getProperty("local.server.port")));
    }

    // ---- the case shapes -------------------------------------------------------

    /**
     * {@code template} is the mapping pattern (what the coverage guard matches on).
     *
     * <p>{@code witnessSql}/{@code witnessArg} are how a DELIBERATE NO-OP is told apart from a
     * leak. This codebase makes deletes idempotent on purpose — a replayed offline op must not be
     * reported back to the master as a failure — so {@code findByIdAnd<Parent>Id(...).ifPresent(...)}
     * answers 204 for an id that is not his, having touched nothing. Status alone cannot
     * distinguish «denied» from «did nothing», so such a case carries a fingerprint of the
     * VICTIM's row and passes only on proof that the row is byte-for-byte what it was.</p>
     */
    private record Case(String method, String template, String path, byte[] body, String contentType,
                        String witnessSql, UUID witnessArg) {
    }

    private record Hit(int status, String body) {
    }

    private record BodyCase(Case request, String what, String fingerprintSql, UUID fingerprintArg) {
    }

    private static Case get(String template, UUID... ids) {
        return new Case("GET", template, fill(template, ids), null, null, null, null);
    }

    private static Case del(String template, UUID... ids) {
        return new Case("DELETE", template, fill(template, ids), null, null, null, null);
    }

    private static Case post(String template, UUID... ids) {
        return new Case("POST", template, fill(template, ids), new byte[0], null, null, null);
    }

    private static Case putNoBody(String template, UUID... ids) {
        return new Case("PUT", template, fill(template, ids), new byte[0], null, null, null);
    }

    private static Case patchNoBody(String template, UUID... ids) {
        return new Case("PATCH", template, fill(template, ids), new byte[0], null, null, null);
    }

    private static Case json(String method, String template, String body, UUID... ids) {
        return new Case(method, template, fill(template, ids),
                body.getBytes(StandardCharsets.UTF_8), JSON, null, null);
    }

    /** A multipart request whose only part is the {@code file} every such endpoint requires. */
    private static Case upload(String method, String template, UUID... ids) {
        return new Case(method, template, fill(template, ids), multipart(Map.of()), MULTIPART, null, null);
    }

    private static Case uploadWith(String method, String template,
                                   String name, String value, UUID... ids) {
        return new Case(method, template, fill(template, ids),
                multipart(Map.of(name, value)), MULTIPART, null, null);
    }

    /**
     * The same request, now watching the victim's row in {@code table}. Hashing the WHOLE row
     * ({@code md5(t::text)}) rather than naming columns means a no-op has to be a no-op in every
     * field — a soft delete that only stamps {@code cleared_at} moves the fingerprint too. A row
     * that is gone prints {@code 'gone'}, so a successful foreign delete can never read as
     * unchanged.
     */
    private static Case watching(Case c, String table, UUID victimRowId) {
        return new Case(c.method(), c.template(), c.path(), c.body(), c.contentType(),
                "select coalesce((select md5(t::text) from " + table + " t where t.id = ?), 'gone')",
                victimRowId);
    }

    /** The same request with a query string. {@code template} stays the registered pattern, so the
     *  coverage guard still matches it. */
    private static Case withQuery(Case c, String query) {
        return new Case(c.method(), c.template(), c.path() + "?" + query, c.body(), c.contentType(),
                c.witnessSql(), c.witnessArg());
    }

    /**
     * A real 1×1 PNG under the part name {@code file}, plus any text parts given. A missing file
     * part would answer 400 and the case would prove nothing about ownership.
     */
    private static byte[] multipart(Map<String, String> textParts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            for (Map.Entry<String, String> e : textParts.entrySet()) {
                out.write(("--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\""
                        + e.getKey() + "\"\r\n\r\n" + e.getValue() + "\r\n")
                        .getBytes(StandardCharsets.UTF_8));
            }
            out.write(("--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"file\"; "
                    + "filename=\"probe.png\"\r\nContent-Type: image/png\r\n\r\n")
                    .getBytes(StandardCharsets.UTF_8));
            out.write(Base64.getDecoder().decode(ONE_PIXEL_PNG));
            out.write(("\r\n--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    private static final String ONE_PIXEL_PNG =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8DwHwAFBQIAX8jx"
                    + "0gAAAABJRU5ErkJggg==";

    /**
     * Substitutes the pattern's {@code {…}} placeholders left to right. Deliberately positional:
     * {@code {id}} means a project, an estimate, an act or a cash entry depending on the controller,
     * so naming them would mislead. A count mismatch is a bug in the case, not a test failure.
     */
    private static String fill(String template, UUID... ids) {
        StringBuilder sb = new StringBuilder();
        int used = 0;
        int i = 0;
        while (i < template.length()) {
            char ch = template.charAt(i);
            if (ch == '{') {
                int close = template.indexOf('}', i);
                if (used >= ids.length) {
                    throw new IllegalArgumentException(
                            "not enough ids for " + template + " (got " + ids.length + ")");
                }
                sb.append(ids[used++]);
                i = close + 1;
            } else {
                sb.append(ch);
                i++;
            }
        }
        if (used != ids.length) {
            throw new IllegalArgumentException(
                    "too many ids for " + template + " (got " + ids.length + ", used " + used + ")");
        }
        return sb.toString();
    }

    // ---- the two masters -------------------------------------------------------

    /**
     * One id per owner-scoped table. Positional, because {@link #fill} is positional — a field is
     * named after what the row IS, not after the path variable that carries it.
     */
    private static final class Ids {
        UUID client;
        UUID project;
        UUID estimate;
        UUID estimateItem;
        UUID estimateShareLink;
        UUID catalogItem;
        UUID notice;
        UUID cash;
        UUID customTrade;
        UUID norm;
        UUID material;
        UUID template;
        UUID templateItem;
        UUID room;
        UUID measurementItem;
        UUID expense;
        UUID payment;
        UUID paymentReceipt;
        UUID note;
        UUID photo;
        UUID photoFolder;
        UUID message;
        UUID messageFile;
        UUID projectReceipt;
        UUID shoppingItem;
        UUID act;
        UUID actReceipt;

        List<UUID> all() {
            return Arrays.asList(client, project, estimate, estimateItem, estimateShareLink, catalogItem,
                    notice, cash, customTrade, norm, material, template, templateItem, room,
                    measurementItem, expense, payment, paymentReceipt, note, photo, photoFolder,
                    message, messageFile, projectReceipt, shoppingItem, act, actReceipt);
        }
    }

    private record Master(UUID userId, String token, Ids ids) {
    }

    /**
     * Every NOT NULL column is supplied explicitly rather than leaned on a database default — a
     * seed that fails to insert is a wasted three-minute run, and the columns are the one thing a
     * later migration can change under this test.
     *
     * <p>Both masters are {@code Plan.TEAM} and {@code emailVerified}: TEAM is what unlocks every
     * gated feature in {@code PlanConfig}, and an unverified master is answered 403
     * {@code EMAIL_NOT_VERIFIED} by {@code POST /estimates/{id}/share} — which would look exactly
     * like an ownership denial while proving nothing.
     */
    private Master seed(String tag) {
        String unique = tag.toLowerCase() + UUID.randomUUID().toString().replace("-", "");
        String email = unique + "@majstr.test";
        UUID owner = userRepository.save(User.builder()
                .email(email).emailCanonical(email).passwordHash("x")
                .fullName("Майстер " + tag).phone("+38000000000" + tag.hashCode() % 10)
                .companyName("ФОП " + tag)
                .plan(Plan.TEAM)
                .emailVerified(true)
                .referralCode(unique.substring(0, 10))
                .build()).getId();

        Ids id = new Ids();
        id.material = jdbc.queryForObject(
                "select id from material order by code limit 1", UUID.class);

        id.client = insert("""
                insert into clients (id, owner_id, full_name, phone, client_type, created_at)
                values (?, ?, ?, '+380991112233', 'PERSON', now())""", owner, "Клієнт " + tag);
        id.project = insert("""
                insert into projects (id, owner_id, client_id, name, address, status,
                        estimates_created, estimates_deleted, created_at, updated_at)
                values (?, ?, ?, ?, 'вул. Тестова, 1', 'IN_PROGRESS', 1, 0, now(), now())""",
                owner, id.client, "Обʼєкт " + tag);
        id.estimate = insert("""
                insert into estimates (id, project_id, name, status, kind, count_in_economy,
                        portal_visible, version, created_at, updated_at)
                values (?, ?, ?, 'DRAFT', 'REGULAR', true, false, 0, now(), now())""",
                id.project, "Кошторис " + tag);
        id.estimateItem = insert("""
                insert into estimate_items (id, estimate_id, type, name, unit, quantity, unit_price,
                        line_total, base_detached, sort_order, quantity_manual)
                values (?, ?, 'WORK', ?, 'M2', 10.000, 100.00, 1000.00, false, 0, false)""",
                id.estimate, "Робота " + tag);
        id.estimateShareLink = insert("""
                insert into estimate_share_links (id, estimate_id, token, revoked, created_at)
                values (?, ?, ?, false, now())""", id.estimate, "tok-est-" + unique);

        id.catalogItem = insert("""
                insert into catalog_items (id, owner_id, name, trade, type, unit, default_price,
                        source, sort_order, created_at)
                values (?, ?, ?, 'PAINTER', 'WORK', 'M2', 150.00, 'MANUAL', 0, now())""",
                owner, "Позиція " + tag);
        id.notice = insert("""
                insert into catalog_update_notices (id, user_id, kind, positions_added,
                        positions_removed, created_at)
                values (?, ?, 'COUNT', 3, 0, now())""", owner);
        id.cash = insert("""
                insert into cash_entry (id, owner_id, direction, amount, happened_on, happened_at,
                        material_refund, created_at)
                values (?, ?, 'INCOME', 500.00, current_date, now(), false, now())""", owner);
        id.customTrade = insert("""
                insert into user_trade (id, user_id, name, sort_order, created_at)
                values (?, ?, ?, 0, now())""", owner, "Свій трейд " + tag);
        id.norm = insert("""
                insert into material_norm (id, owner_id, name_key, unit, material_id, qty_per_unit,
                        basis, waste_percent, sort_order, created_at)
                values (?, ?, ?, 'M2', ?, 0.250, 'QUANTITY', 0, 0, now())""",
                owner, "норма " + unique, id.material);

        id.template = insert("""
                insert into estimate_templates (id, owner_id, name, is_default, created_at,
                        updated_at)
                values (?, ?, ?, false, now(), now())""", owner, "Набір " + tag);
        id.templateItem = insert("""
                insert into estimate_template_items (id, template_id, name, type, unit, sort_order)
                values (?, ?, ?, 'WORK', 'M2', 0)""", id.template, "Позиція набору " + tag);

        id.room = insert("""
                insert into measurement_room (id, project_id, name, sort_order, created_at)
                values (?, ?, ?, 0, now())""", id.project, "Кімната " + tag);
        id.measurementItem = insert("""
                insert into measurement_item (id, room_id, name, type, unit, result, payload,
                        sort_order)
                values (?, ?, ?, 'SURFACE', 'M2', 8.100, '{"width":3,"height":2.7}', 0)""",
                id.room, "Стіна " + tag);

        id.expense = insert("""
                insert into object_expenses (id, object_id, amount, category, source, spent_at,
                        created_at)
                values (?, ?, 300.00, 'MATERIALS', 'MANUAL', current_date, now())""", id.project);
        id.payment = insert("""
                insert into project_payment (id, project_id, amount, purpose, sort_order, created_at)
                values (?, ?, 1000.00, ?, 0, now())""", id.project, "Аванс " + tag);
        id.paymentReceipt = insert("""
                insert into payment_receipt (id, project_id, plan_payment_id, amount, received_at,
                        material_refund, created_at)
                values (?, ?, ?, 400.00, current_date, false, now())""", id.project, id.payment);

        id.note = insert("""
                insert into project_note (id, project_id, body, sort_order, created_at, updated_at)
                values (?, ?, ?, 0, now(), now())""", id.project, "Нотатка " + tag);
        id.photoFolder = insert("""
                insert into project_photo_folder (id, project_id, name, created_at)
                values (?, ?, ?, now())""", id.project, "Тека " + tag);
        id.photo = insert("""
                insert into project_photo (id, project_id, storage_key, source, visibility,
                        created_at)
                values (?, ?, ?, 'MANUAL', 'PRIVATE', now())""",
                id.project, "photos/" + unique + ".png");

        id.message = insert("""
                insert into project_messages (id, project_id, message, is_read, created_at)
                values (?, ?, ?, false, now())""", id.project, "Питання клієнта " + tag);
        id.messageFile = insert("""
                insert into project_message_files (id, message_id, storage_key, content_type,
                        size_bytes, created_at)
                values (?, ?, ?, 'image/png', 68, now())""",
                id.message, "messages/" + unique + ".png");

        id.projectReceipt = insert("""
                insert into project_receipt (id, project_id, label, amount, reimbursable,
                        sort_order, version, created_at, updated_at)
                values (?, ?, ?, 250.00, true, 0, 0, now(), now())""", id.project, "Чек " + tag);

        UUID shoppingList = insert("""
                insert into shopping_list (id, project_id, created_at, updated_at)
                values (?, ?, now(), now())""", id.project);
        id.shoppingItem = insert("""
                insert into shopping_list_item (id, shopping_list_id, name, unit, quantity, bought,
                        edited, source, sort_order, created_at, updated_at)
                values (?, ?, ?, 'PIECE', 2.000, false, false, 'MANUAL', 0, now(), now())""",
                shoppingList, "Клей " + tag);

        id.act = insert("""
                insert into work_act (id, user_id, project_id, number, kind, status, issued_at,
                        period_from, period_to, show_materials, show_cumulative,
                        receipts_to_expenses, show_receipt_photos, signed_offline, version,
                        created_at, updated_at)
                values (?, ?, ?, 1, 'INTERIM', 'DRAFT', current_date, current_date, current_date,
                        true, false, false, true, false, 0, now(), now())""", owner, id.project);
        id.actReceipt = insert("""
                insert into work_act_receipt (id, work_act_id, label, amount, returned_amount,
                        itemized, sort_order, version, created_at)
                values (?, ?, ?, 120.00, 0.00, false, 0, 0, now())""", id.act, "Чек акта " + tag);

        assertThat(id.all())
                .as("every owner-scoped table needs a seeded row, or its cases probe nothing")
                .doesNotContainNull();
        return new Master(owner, jwtService.generateAccessToken(owner, email), id);
    }

    /** Inserts one row with a fresh id as the first bind parameter and returns that id. */
    private UUID insert(String sql, Object... args) {
        UUID id = UUID.randomUUID();
        Object[] bind = new Object[args.length + 1];
        bind[0] = id;
        System.arraycopy(args, 0, bind, 1, args.length);
        jdbc.update(sql, bind);
        return id;
    }
}
