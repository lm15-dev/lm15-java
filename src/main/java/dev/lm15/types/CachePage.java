package dev.lm15.types;

import java.util.List;

/** One page of stored cache objects plus an opaque continuation cursor. */
public record CachePage(List<CacheInfo> items, String nextCursor) {
    public CachePage {
        items = Check.list(items, "CachePage.items");
        Check.optNonEmpty(nextCursor, "CachePage.next_cursor");
    }
}
