package org.example.vo;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class ContentGapDetailView {
    private ContentGapView gap;
    private List<ContentGapEventView> events;
}
