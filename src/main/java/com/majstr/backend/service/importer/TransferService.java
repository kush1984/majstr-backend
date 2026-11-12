package com.majstr.backend.service.importer;

import com.majstr.backend.config.LocalizationConfig;
import com.majstr.backend.dto.CatalogImportCommitResponse;
import com.majstr.backend.dto.ClientRequest;
import com.majstr.backend.dto.ClientResponse;
import com.majstr.backend.dto.PaymentReceiptRequest;
import com.majstr.backend.dto.TransferCommitRequest;
import com.majstr.backend.dto.TransferCommitRequest.EstimateDoc;
import com.majstr.backend.dto.TransferCommitResponse;
import com.majstr.backend.dto.TransferParseResponse;
import com.majstr.backend.entity.ItemType;
import com.majstr.backend.entity.Project;
import com.majstr.backend.entity.Trade;
import com.majstr.backend.entity.Unit;
import com.majstr.backend.entity.User;
import com.majstr.backend.exception.ResourceNotFoundException;
import com.majstr.backend.exception.TransferValidationException;
import com.majstr.backend.feature.Feature;
import com.majstr.backend.feature.FeatureGuard;
import com.majstr.backend.repository.ProjectRepository;
import com.majstr.backend.repository.UserRepository;
import com.majstr.backend.service.ClientService;
import com.majstr.backend.service.EstimateService;
import com.majstr.backend.service.EstimateService.ImportEstimateData;
import com.majstr.backend.service.EstimateService.ImportEstimateData.ImportItem;
import com.majstr.backend.service.PaymentService;
import com.majstr.backend.service.ProjectService;
import com.majstr.backend.service.importer.CatalogImportService.FiledItem;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * «Перенести в Majstr»: a master's existing estimates and price lists, many files at once.
 *
 * <p>{@link #parse} reads ONE file (the PWA sends them one by one, with progress) and says what it
 * is — a price list or an estimate, the object and client the paper names — with every position
 * filed under a trade by {@link TradeFiler}. {@link #commit} takes the confirmed review in ONE
 * transaction: the catalog first (so the estimates' lines resolve their trades from it), the
 * master's profile gains the trades his positions landed in, then each estimate lands on an object
 * — an existing one, or a new one created OUTSIDE the FREE cap (V201) but within
 * {@link #MAX_TRANSFERRED_OBJECTS}.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TransferService {

    /** How many objects a master may bring in this way over the account's life — a bound, so the
     *  door that skips the FREE cap is not an unlimited one. */
    static final int MAX_TRANSFERRED_OBJECTS = 50;
    private static final String DEPOSIT_LABEL = "Завдаток";

    private final FeatureGuard featureGuard;
    private final UserRepository userRepository;
    private final ProjectRepository projectRepository;
    private final DocumentReader documentReader;
    private final EstimateExtractor extractor;
    private final TradeFiler tradeFiler;
    private final CatalogImportService catalogImportService;
    private final ProjectService projectService;
    private final ClientService clientService;
    private final EstimateService estimateService;
    private final PaymentService paymentService;

    public TransferParseResponse parse(UUID ownerId, String filename, String contentType, byte[] bytes) {
        featureGuard.requireFeature(loadUser(ownerId), Feature.ESTIMATE_IMPORT);
        EstimateExtractor.Document doc = extractor.extractDocument(documentReader.read(filename, contentType, bytes,
                "Read this document: say whether it is an estimate or a price list, and extract its positions."));
        boolean priceList = doc.kind() == EstimateExtractor.DocumentKind.PRICE_LIST;

        List<EstimateExtractor.Extracted.Line> lines = doc.extracted().items();
        List<TradeFiler.Filing> filings = tradeFiler.file(ownerId, lines.stream()
                .map(l -> new TradeFiler.Position(l.name(), type(l.type()))).toList());
        List<TransferParseResponse.Line> out = new ArrayList<>(lines.size());
        for (int i = 0; i < lines.size(); i++) {
            EstimateExtractor.Extracted.Line l = lines.get(i);
            Unit unit = UnitNormalizer.normalize(l.unit());
            BigDecimal quantity = priceList ? BigDecimal.ZERO : l.quantity();
            List<String> issues = new ArrayList<>();
            if (unit == null) issues.add("unit");
            if (!priceList && (quantity == null || quantity.signum() <= 0)) issues.add("quantity");
            if (l.unitPrice() == null || l.unitPrice().signum() <= 0) issues.add("price");
            TradeFiler.Filing filing = filings.get(i);
            out.add(new TransferParseResponse.Line(l.name().trim(), unit, quantity, l.unitPrice(), type(l.type()),
                    blankToNull(l.category()), filing.trade(), filing.category(), issues));
        }
        return new TransferParseResponse(filename, doc.kind(), doc.objectName(), doc.address(), doc.clientName(),
                doc.title(), priceList ? null : doc.extracted().depositAmount(), out);
    }

    @Transactional
    public TransferCommitResponse commit(UUID ownerId, TransferCommitRequest req) {
        User user = userRepository.findWithTradesById(ownerId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + ownerId));
        featureGuard.requireFeature(user, Feature.ESTIMATE_IMPORT);
        for (EstimateDoc doc : req.estimates()) {
            if (doc.projectId() == null && (doc.objectName() == null || doc.objectName().isBlank())) {
                throw new TransferValidationException("error.transfer.no-object");
            }
        }
        long newObjects = req.estimates().stream()
                .filter(d -> d.projectId() == null)
                .map(d -> key(d.objectName()))
                .distinct()
                .count();
        if (projectRepository.countByOwnerIdAndTransferredAtIsNotNull(ownerId) + newObjects > MAX_TRANSFERRED_OBJECTS) {
            throw new TransferValidationException("error.transfer.too-many-objects");
        }

        // 1. The catalog first: the estimates' lines take their trades from it.
        List<FiledItem> catalog = new ArrayList<>();
        Set<Trade> trades = EnumSet.noneOf(Trade.class);
        for (TransferCommitRequest.PriceList list : req.priceLists()) {
            for (TransferCommitRequest.CatalogLine l : list.items()) {
                catalog.add(new FiledItem(l.name(), l.unit(), l.price(), l.type(), l.trade(), l.category(), null));
                track(trades, l.trade());
            }
        }
        for (EstimateDoc doc : req.estimates()) {
            if (Boolean.FALSE.equals(doc.toCatalog())) {
                continue;
            }
            for (TransferCommitRequest.EstimateLine l : doc.items()) {
                catalog.add(new FiledItem(l.name(), l.unit(), l.unitPrice(), l.type(), l.trade(), l.category(), null));
                track(trades, l.trade());
            }
        }
        CatalogImportCommitResponse cat = catalog.isEmpty() ? new CatalogImportCommitResponse(0, 0, 0)
                : catalogImportService.commitFiled(ownerId, catalog, req.defaultPolicy());

        // 2. A position filed under a trade the master never picked would sit behind a chip he does
        //    not have: his profile gains it.
        user.getTrades().addAll(trades);

        // 3. Estimates onto objects — one new object per distinct name in this batch.
        Map<String, UUID> created = new HashMap<>();
        Map<String, UUID> clients = new HashMap<>();
        List<UUID> projectIds = new ArrayList<>();
        int estimates = 0;
        for (EstimateDoc doc : req.estimates()) {
            UUID projectId = doc.projectId();
            if (projectId == null) {
                String k = key(doc.objectName());
                projectId = created.get(k);
                if (projectId == null) {
                    UUID clientId = clientFor(ownerId, doc.clientName(), clients);
                    Project p = projectService.createTransferred(doc.objectName(), doc.address(), clientId, ownerId);
                    projectId = p.getId();
                    created.put(k, projectId);
                    projectIds.add(projectId);
                }
            }
            estimateService.createFromImport(projectId, new ImportEstimateData(blankToNull(doc.estimateName()),
                    doc.items().stream().map(l -> new ImportItem(l.type(), l.name(), l.section(), l.unit(),
                            l.quantity(), l.unitPrice())).toList()), ownerId);
            estimates++;
            if (doc.depositAmount() != null && doc.depositAmount().signum() > 0) {
                paymentService.addReceipt(projectId, ownerId, new PaymentReceiptRequest(DEPOSIT_LABEL,
                        doc.depositAmount(), LocalDate.now(LocalizationConfig.ZONE), false), null);
            }
        }
        log.info("Transfer for {}: {} new object(s), {} estimate(s), catalog created={} updated={} skipped={}",
                ownerId, created.size(), estimates, cat.created(), cat.updated(), cat.skipped());
        return new TransferCommitResponse(created.size(), estimates, cat.created(), cat.updated(), cat.skipped(),
                projectIds);
    }

    /** The client the paper names: one he already has under that name, else a new one (name only). */
    private UUID clientFor(UUID ownerId, String name, Map<String, UUID> seen) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String k = key(name);
        UUID known = seen.get(k);
        if (known != null) {
            return known;
        }
        UUID id = clientService.listForOwner(ownerId).stream()
                .filter(c -> key(c.fullName()).equals(k))
                .map(ClientResponse::id)
                .findFirst()
                .orElseGet(() -> clientService.create(new ClientRequest(name.trim(), null, null, null, null,
                        null, null, null, null, null), ownerId).id());
        seen.put(k, id);
        return id;
    }

    private static void track(Set<Trade> trades, Trade trade) {
        if (trade != null && trade != Trade.OTHER) {
            trades.add(trade);
        }
    }

    private static String key(String s) {
        return s == null ? "" : s.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static ItemType type(String raw) {
        if (raw == null) {
            return ItemType.WORK;
        }
        String s = raw.trim().toUpperCase(Locale.ROOT);
        return s.startsWith("MAT") || s.contains("МАТЕР") ? ItemType.MATERIAL : ItemType.WORK;
    }

    private User loadUser(UUID ownerId) {
        return userRepository.findById(ownerId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + ownerId));
    }
}
