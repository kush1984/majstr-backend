package com.majstr.backend.entity;

/** How a {@link CatalogUpdateNotice} left the queue (V179). Null on rows closed before V179. */
public enum CatalogUpdateNoticeResolution {
    /** «Лишити як є» / «Зрозуміло». */
    DISMISSED,
    /** «Оновити мою ціну» — the new price was applied. */
    ACCEPTED,
    /** The master set the position's price himself, which answered the notice's question. */
    RESOLVED
}
