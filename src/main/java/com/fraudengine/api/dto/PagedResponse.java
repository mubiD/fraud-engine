package com.fraudengine.api.dto;

import java.time.Instant;
import java.util.List;

public class PagedResponse<T> {

    private List<T> data;
    private Instant nextCursor;
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
