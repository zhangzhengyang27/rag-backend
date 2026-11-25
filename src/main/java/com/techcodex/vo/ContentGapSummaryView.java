package com.techcodex.vo;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ContentGapSummaryView {
    private long total;
    private long open;
    private long triaged;
    private long planned;
    private long resolved;
    private long last7Days;
}
