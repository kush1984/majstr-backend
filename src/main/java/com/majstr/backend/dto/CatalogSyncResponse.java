package com.majstr.backend.dto;

/** What «Оновити каталог» actually wrote: positions re-priced, positions added. */
public record CatalogSyncResponse(int updated, int added) {}
