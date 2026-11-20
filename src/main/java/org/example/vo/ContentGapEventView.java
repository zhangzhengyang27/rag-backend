package org.example.vo;

import lombok.Builder;
import lombok.Data;

import java.util.Date;

@Data
@Builder
public class ContentGapEventView {
    private Long id;
    private String signalType;
    private String conversationId;
    private Long feedbackId;
    private String detail;
    private Date createdDate;
}
