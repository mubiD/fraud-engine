package com.fraudengine.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

@Schema(description = "Cursor-paginated response envelope")
public class PagedResponse<T> {

    @Schema(description = "Page of results")
    private List<T> data;

    @Schema(description = "ISO-8601 cursor to pass as the `cursor` parameter to fetch the next page; null when there are no more pages",
            example = "2026-07-23T09:00:00Z")
    private Instant nextCursor;

    @Schema(description = "True if there are additional pages beyond this one")
    private boolean hasMore;

    public PagedResponse() {}

    public List<T> getData() { return data; }
    public Instant getNextCursor() { return nextCursor; }
    public boolean isHasMore() { return hasMore; }

    public void setData(List<T> v) { this.data = v; }
    public void setNextCursor(Instant v) { this.nextCursor = v; }
    public void setHasMore(boolean v) { this.hasMore = v; }

    public static <T> Builder<T> builder() { return new Builder<>(); }

    public static class Builder<T> {
        private final PagedResponse<T> r = new PagedResponse<>();
        public Builder<T> data(List<T> v) { r.data = v; return this; }
        public Builder<T> nextCursor(Instant v) { r.nextCursor = v; return this; }
        public Builder<T> hasMore(boolean v) { r.hasMore = v; return this; }
        public PagedResponse<T> build() { return r; }
    }
}
