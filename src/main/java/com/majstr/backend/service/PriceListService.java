package com.majstr.backend.service;

import com.lowagie.text.DocumentException;
import com.majstr.backend.config.LocalizationConfig;
import com.majstr.backend.config.PortalProperties;
import com.majstr.backend.dto.CatalogItemResponse;
import com.majstr.backend.dto.ClientPreviewResponse;
import com.majstr.backend.dto.PriceListRequest;
import com.majstr.backend.dto.PriceListResponse;
import com.majstr.backend.dto.PublicEstimateView;
import com.majstr.backend.dto.PublicPriceListView;
import com.majstr.backend.entity.ItemType;
import com.majstr.backend.entity.PriceListLink;
import com.majstr.backend.entity.User;
import com.majstr.backend.exception.ResourceNotFoundException;
import com.majstr.backend.feature.Feature;
import com.majstr.backend.feature.FeatureGuard;
import com.majstr.backend.repository.CatalogItemRepository;
import com.majstr.backend.repository.PriceListLinkRepository;
import com.majstr.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;

import java.io.IOException;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * «Поділитися прайсом» (V205): the master ticks works in his catalog, the client gets a tokenised page and
 * a PDF with those works and their CURRENT catalog prices.
 *
 * <p>Only priced works can be on it — a 0 ₴ row or a material is refused at the write and filtered again
 * at the read, so a price the master later clears to 0 disappears from the client's page by itself.</p>
 */
@Service
@RequiredArgsConstructor
public class PriceListService {

    static final String PATH = "/portal/index.html?l=";
    static final String PREVIEW_PATH = "/portal/index.html?vl=";
    private static final int TOKEN_BYTES = 32;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    /** {@code id IN ()} is not SQL; an empty pick is passed as an id no row has. */
    private static final UUID NO_ROW = new UUID(0, 0);

    private final CatalogService catalogService;
    private final CatalogItemRepository catalogRepository;
    private final PriceListLinkRepository linkRepository;
    private final UserRepository userRepository;
    private final FeatureGuard featureGuard;
    private final PortalProperties portalProperties;
    private final PriceListPdfService pdfService;
    private final HeavyWork heavyWork;
    private final PlatformTransactionManager transactionManager;
    private final PortalPreviewTokens previewTokens;

    public PriceListResponse state(UUID ownerId) {
        return Transactions.read(transactionManager, () -> stateOf(ownerId));
    }

    /** Saves the ticks and makes sure a live link exists — the share itself happens on the phone. */
    public PriceListResponse save(UUID ownerId, PriceListRequest request) {
        return Transactions.write(transactionManager, () -> {
            // Locked: two taps at once («Поділитися», then «Копіювати») would both find no live link and
            // both insert one — the partial unique index then fails the second with a 500.
            User owner = userRepository.findByIdForUpdate(ownerId)
                    .orElseThrow(() -> new ResourceNotFoundException("User not found"));
            requireSharable(owner);
            writeTicks(ownerId, request);
            if (linkRepository.findFirstByOwnerIdAndRevokedFalse(ownerId).isEmpty()) {
                linkRepository.save(PriceListLink.builder().owner(owner).token(generateToken()).build());
            }
            return stateOf(ownerId);
        });
    }

    /**
     * «Подивитися, як побачить клієнт» — the ticks are saved, but NO link is minted: the page opens on a
     * ten-minute pass ({@link PortalPreviewTokens}), as an estimate's preview does. Looking is not sharing.
     * The name is not required, as for an estimate: the empty header is part of what he should see.
     */
    public ClientPreviewResponse preview(UUID ownerId, PriceListRequest request) {
        return Transactions.write(transactionManager, () -> {
            User owner = userRepository.findByIdForUpdate(ownerId)
                    .orElseThrow(() -> new ResourceNotFoundException("User not found"));
            featureGuard.requireFeature(owner, Feature.CLIENT_PORTAL);
            writeTicks(ownerId, request);
            return new ClientPreviewResponse(
                    portalProperties.publicBaseUrl() + PREVIEW_PATH + previewTokens.issuePriceList(ownerId),
                    previewTokens.expiresAt());
        });
    }

    /** The page behind a preview pass: what the master has ticked, exactly as a client would read it. */
    public PublicPriceListView previewView(String pass) {
        UUID ownerId = previewTokens.priceListOf(pass)
                .orElseThrow(() -> new ResourceNotFoundException("Preview pass expired or invalid"));
        return Transactions.read(transactionManager, () -> viewOf(loadOwner(ownerId)));
    }

    /** «Закрити посилання»: the client's page stops opening. The ticks stay; the next share mints a new link. */
    public PriceListResponse revoke(UUID ownerId) {
        return Transactions.write(transactionManager, () -> {
            linkRepository.findFirstByOwnerIdAndRevokedFalse(ownerId).ifPresent(link -> link.setRevoked(true));
            return stateOf(ownerId);
        });
    }

    public PublicPriceListView publicView(String token) {
        return Transactions.read(transactionManager, () -> viewOf(resolve(token)));
    }

    public byte[] renderPdf(UUID ownerId) throws IOException, DocumentException {
        PriceListPdfService.PdfModel model = Transactions.read(transactionManager, () -> {
            User owner = loadOwner(ownerId);
            SendReadiness.masterSendsItself(owner);
            return pdfModel(owner);
        });
        return heavyWork.pdf(() -> pdfService.render(model));
    }

    public byte[] renderPublicPdf(String token) throws IOException, DocumentException {
        PriceListPdfService.PdfModel model = Transactions.read(transactionManager, () -> pdfModel(resolve(token)));
        return heavyWork.pdf(() -> pdfService.render(model));
    }

    // ---- internals ------------------------------------------------------------------------------

    private void writeTicks(UUID ownerId, PriceListRequest request) {
        List<UUID> ids = request.itemIds().isEmpty() ? List.of(NO_ROW) : request.itemIds();
        catalogRepository.setPriceList(ownerId, ids);
    }

    private PriceListResponse stateOf(UUID ownerId) {
        List<UUID> libraryPriced = catalogRepository.findLibraryPricedIds(ownerId);
        List<UUID> saved = catalogRepository.findPriceListIds(ownerId);
        // He has chosen once he has shared, or ticked and looked (a preview saves the ticks, mints no link).
        boolean configured = !saved.isEmpty() || linkRepository.existsByOwnerId(ownerId);
        List<UUID> ticked = configured ? saved : suggested(ownerId, new HashSet<>(libraryPriced));
        String url = linkRepository.findFirstByOwnerIdAndRevokedFalse(ownerId)
                .map(link -> portalProperties.publicBaseUrl() + PATH + link.getToken())
                .orElse(null);
        return new PriceListResponse(url, configured, ticked, libraryPriced);
    }

    /** Before the first share: every priced work the master has put his own price on. */
    private List<UUID> suggested(UUID ownerId, Set<UUID> libraryPriced) {
        return catalogService.listForOwner(ownerId, ItemType.WORK).stream()
                .filter(i -> !i.hidden() && isPriced(i) && !libraryPriced.contains(i.id()))
                .map(CatalogItemResponse::id)
                .toList();
    }

    private PriceListPdfService.PdfModel pdfModel(User owner) {
        String logoKey = featureGuard.isEnabled(owner, Feature.BRANDED_PDF) ? owner.getLogoUrl() : null;
        return new PriceListPdfService.PdfModel(viewOf(owner), logoKey);
    }

    private User resolve(String token) {
        return linkRepository.findByTokenAndRevokedFalse(token)
                .map(PriceListLink::getOwner)
                .orElseThrow(() -> new ResourceNotFoundException("Price list not found"));
    }

    /**
     * The ticked works in the master's own order, grouped trade → category. Trades come in the order
     * their first position appears; a category sits where the library's sequence puts it, the master's
     * own categories after.
     */
    PublicPriceListView viewOf(User owner) {
        Set<UUID> ticked = new HashSet<>(catalogRepository.findPriceListIds(owner.getId()));
        Map<String, Map<String, List<CatalogItemResponse>>> trades = new LinkedHashMap<>();
        Map<String, String> tradeNames = new LinkedHashMap<>();
        Map<String, Integer> categoryOrder = new LinkedHashMap<>();
        for (CatalogItemResponse item : catalogService.listForOwner(owner.getId(), ItemType.WORK)) {
            // A trade taken off the profile hides its rows from his catalog, so he cannot untick them there:
            // they leave the client's page with it.
            if (!ticked.contains(item.id()) || !isPriced(item) || item.hidden()) {
                continue;
            }
            String tradeKey = item.customTradeId() != null ? "custom:" + item.customTradeId()
                    : String.valueOf(item.trade());
            tradeNames.putIfAbsent(tradeKey, item.customTradeId() != null && item.customTradeName() != null
                    ? item.customTradeName() : TradeLabel.ua(item.trade()));
            String category = blankToNull(item.category());
            categoryOrder.putIfAbsent(tradeKey + "|" + category,
                    item.categoryOrder() == null ? Integer.MAX_VALUE : item.categoryOrder());
            trades.computeIfAbsent(tradeKey, k -> new LinkedHashMap<>())
                    .computeIfAbsent(category, k -> new ArrayList<>())
                    .add(item);
        }
        List<PublicPriceListView.Trade> result = new ArrayList<>();
        trades.forEach((tradeKey, categories) -> {
            List<String> order = new ArrayList<>(categories.keySet());
            order.sort(Comparator.comparingInt(c -> categoryOrder.get(tradeKey + "|" + c)));
            List<PublicPriceListView.Section> sections = order.stream()
                    .map(c -> new PublicPriceListView.Section(c, categories.get(c).stream()
                            .map(i -> new PublicPriceListView.Item(i.name(), blankToNull(i.description()),
                                    UnitLabel.ua(i.unit()), i.defaultPrice()))
                            .toList()))
                    .toList();
            result.add(new PublicPriceListView.Trade(tradeNames.get(tradeKey), sections));
        });
        return new PublicPriceListView(contractorOf(owner), LocalDate.now(LocalizationConfig.ZONE), result);
    }

    private static PublicEstimateView.Contractor contractorOf(User owner) {
        return new PublicEstimateView.Contractor(ContractorName.display(owner), owner.getFullName(),
                ContractorName.phone(owner),
                owner.getLogoUrl() == null ? null : "/api/files/" + owner.getLogoUrl());
    }

    private static boolean isPriced(CatalogItemResponse item) {
        return item.defaultPrice() != null && item.defaultPrice().signum() > 0;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private User loadOwner(UUID ownerId) {
        return userRepository.findById(ownerId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
    }

    private void requireSharable(User owner) {
        featureGuard.requireFeature(owner, Feature.CLIENT_PORTAL);
        SendReadiness.masterSendsItself(owner);
    }

    private static String generateToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        return ENCODER.encodeToString(bytes);
    }
}
