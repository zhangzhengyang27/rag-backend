package com.techcodex.vo;

import lombok.Builder;
import lombok.Data;

import java.util.Date;

@Data
@Builder
public class ContentGapClusterView {
    private String key;
    private String label;
    private Integer size;
    private Date lastSeen;
}
