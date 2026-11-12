package com.majstr.backend.dto;

import com.majstr.backend.dto.CatalogImportCommitRequest.DedupPolicy;
import com.majstr.backend.entity.ItemType;
import com.majstr.backend.entity.Trade;
import com.majstr.backend.entity.Unit;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * What the master confirmed on the «Перенести в Majstr» review: price lists go to the catalog,
 * estimates to objects — an existing one ({@code projectId}) or a new one named {@code objectName}
 * (two estimates naming the same new object land in ONE object). Each line carries the trade and
 * category it is filed under.
 */
public record TransferCommitRequest(
        @NotNull @Size(max = 50) @Valid List<PriceList> priceLists,
        @NotNull @Size(max = 50) @Valid List<EstimateDoc> estimates,
        @NotNull DedupPolicy defaultPolicy
) {
    public record PriceList(@NotEmpty @Size(max = 1000) @Valid List<CatalogLine> items) {}

    public record CatalogLine(
            @NotBlank @Size(max = 255) String name,
            @NotNull Unit unit,
            @NotNull @DecimalMin("0.0") BigDecimal price,
            @NotNull ItemType type,
            Trade trade,
            @Size(max = 100) String category
    ) {}

    public record EstimateDoc(
            UUID projectId,
            @Size(max = 255) String objectName,
            @Size(max = 512) String address,
            @Size(max = 255) String clientName,
            @Size(max = 255) String estimateName,
            @DecimalMin("0.0") BigDecimal depositAmount,
            /** Null = yes: an estimate's positions also go to the catalog unless the master said no. */
            Boolean toCatalog,
            @NotEmpty @Size(max = 1000) @Valid List<EstimateLine> items
    ) {}

    public record EstimateLine(
            @NotBlank @Size(max = 255) String name,
            @NotNull Unit unit,
            @NotNull @DecimalMin("0.0") BigDecimal quantity,
            @NotNull @DecimalMin("0.0") BigDecimal unitPrice,
            @NotNull ItemType type,
            @Size(max = 100) String section,
            Trade trade,
            @Size(max = 100) String category
    ) {}
}
