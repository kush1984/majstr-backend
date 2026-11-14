package com.majstr.backend.service;

import com.majstr.backend.dto.ProjectResponse;
import com.majstr.backend.entity.CatalogItem;
import com.majstr.backend.entity.CatalogTemplate;
import com.majstr.backend.entity.Estimate;
import com.majstr.backend.entity.EstimateItem;
import com.majstr.backend.entity.ItemType;
import com.majstr.backend.entity.Project;
import com.majstr.backend.entity.ProjectStatus;
import com.majstr.backend.entity.Trade;
import com.majstr.backend.entity.User;
import com.majstr.backend.exception.ResourceNotFoundException;
import com.majstr.backend.exception.SampleProjectExistsException;
import com.majstr.backend.repository.CatalogItemRepository;
import com.majstr.backend.repository.CatalogTemplateRepository;
import com.majstr.backend.repository.EstimateItemRepository;
import com.majstr.backend.repository.EstimateRepository;
import com.majstr.backend.repository.ProjectRepository;
import com.majstr.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * «Подивитися приклад» (V197): an object the server fills itself — one priced estimate of a job the
 * master's trade knows ({@link SampleObjects}) — so a newcomer can walk the whole road (the client's
 * view, sending it to himself, the signature, the PDF) without spending one of his two free objects.
 *
 * <p>Outside the cap: {@link LimitService#reserveProjectSlot} is never called. Deleted freely:
 * {@link ProjectService#delete} skips the B-70 guard for it. Read by no figure: every owner-wide
 * count filters {@code projects.sample}. One live sample per account (409 {@code SAMPLE_PROJECT_EXISTS}),
 * held by the user-row lock here and by the partial unique index under it.</p>
 *
 * <p>Each line is priced like an applied bundle — from the master's own catalog by name — and, where
 * he does not stock the position (a trade he did not pick, a renamed row), from the library: the
 * sample is there to show what a finished estimate looks like, and a 0 ₴ row would show the opposite.
 * A line the library no longer knows is left out rather than shown at zero.</p>
 */
@Service
@RequiredArgsConstructor
public class SampleProjectService {

    static final String DESCRIPTION = "Навчальний приклад: так виглядає готовий кошторис. "
            + "Подивіться, як його побачить клієнт, надішліть собі, підпишіть у порталі, завантажте PDF. "
            + "Він не рахується в ліміт обʼєктів — видаліть, коли не потрібен.";
    static final String ESTIMATE_NAME = "Кошторис";

    private final UserRepository userRepository;
    private final ProjectRepository projectRepository;
    private final EstimateRepository estimateRepository;
    private final EstimateItemRepository estimateItemRepository;
    private final CatalogItemRepository catalogRepository;
    private final CatalogTemplateRepository libraryRepository;
    private final CatalogFiling catalogFiling;
    private final ProjectService projectService;

    @Transactional
    public ProjectResponse create(UUID ownerId, Trade requested) {
        // The user row serialises a double tap; the partial unique index is the floor under it.
        User owner = userRepository.findByIdForUpdate(ownerId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + ownerId));
        if (projectRepository.existsByOwnerIdAndSampleTrue(ownerId)) {
            throw new SampleProjectExistsException();
        }
        SampleObjects.Sample sample = SampleObjects.choose(requested, owner.getTrades());

        Project project = projectRepository.save(Project.builder()
                .owner(owner)
                .name(sample.title())
                .description(DESCRIPTION)
                .status(ProjectStatus.DRAFT)
                .sample(true)
                .build());
        Estimate estimate = estimateRepository.save(Estimate.builder()
                .project(project)
                .name(ESTIMATE_NAME)
                .build());

        List<EstimateItem> items = lines(estimate, sample, ownerId);
        EstimateMath.recalculate(items);
        estimateItemRepository.saveAll(items);
        return projectService.get(project.getId(), ownerId);
    }

    private List<EstimateItem> lines(Estimate estimate, SampleObjects.Sample sample, UUID ownerId) {
        Trade trade = sample.trade();
        Map<String, CatalogItem> catalog = new HashMap<>();
        for (CatalogItem item : catalogRepository.findByOwnerIdOrderByNameAsc(ownerId)) {
            catalog.putIfAbsent(NameKeys.of(item.getName()), item);
        }
        Map<String, CatalogTemplate> library = new HashMap<>();
        for (CatalogTemplate row : libraryRepository.findByTradeInOrderBySortOrderAsc(List.of(trade))) {
            library.putIfAbsent(NameKeys.of(row.getName()) + "|" + row.getUnit(), row);
        }
        Map<String, String> folders = catalogFiling.categoriesUnder(trade);

        List<EstimateItem> items = new ArrayList<>();
        for (SampleObjects.Line line : sample.lines()) {
            CatalogItem own = catalog.get(NameKeys.of(line.name()));
            // His row counts only in the sample's own unit: an m² price on a running-metre line is a
            // different job, and the quantity was written for this unit.
            CatalogItem match = own != null && own.getUnit() == line.unit() ? own : null;
            CatalogTemplate shipped = library.get(NameKeys.of(line.name()) + "|" + line.unit());
            if (match == null && shipped == null) {
                continue;
            }
            ItemType type = match != null ? match.getType() : shipped.getType();
            CatalogFiling.Filing filing = match != null
                    ? CatalogFiling.fileUnder(trade, folders, line.name(), type, line.unit(), match)
                    : new CatalogFiling.Filing(trade, shipped.getCategory());
            items.add(EstimateItem.builder()
                    .estimate(estimate)
                    .type(type)
                    .name(line.name())
                    .category(filing.category())
                    .trade(filing.trade())
                    .description(match != null ? match.getDescription() : shipped.getDescription())
                    .note(match != null ? match.getNote() : null)
                    .unit(line.unit())
                    .quantity(line.quantity())
                    .unitPrice(match != null ? match.getDefaultPrice() : shipped.getSuggestedPrice())
                    .sortOrder(items.size())
                    .build());
        }
        return items;
    }
}
