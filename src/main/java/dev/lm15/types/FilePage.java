package dev.lm15.types;

import java.util.List;

/** One page of stored files plus an opaque continuation cursor (null = complete). */
public record FilePage(List<FileInfo> items, String nextCursor) {
    public FilePage {
        items = Check.list(items, "FilePage.items");
        Check.optNonEmpty(nextCursor, "FilePage.next_cursor");
    }
}
