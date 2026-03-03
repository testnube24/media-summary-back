package com.mediasummary.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Job {
    
    private Long id;
    private String email;
    private String status; // QUEUED, PROCESSING, COMPLETED, ERROR
    private String audioUrl;
    private String transcriptId;
    private String miniSummary;
    private Integer progressPercent;
    private String statusDetail;
    private LocalDateTime createdAt;
    private LocalDateTime completedAt;
    private Integer processingTimeMs;
    private String errorMessage;
    
}
