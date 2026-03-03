package com.mediasummary.controller;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.validation.constraints.Email;
import javax.validation.constraints.NotBlank;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.mediasummary.model.Job;
import com.mediasummary.service.JobQueueService;
import com.mediasummary.service.JobService;
import com.mediasummary.service.ObjectStorageService;
import com.mediasummary.service.ObjectStorageService.StoredObject;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@RestController
@RequestMapping("/v1/jobs")
@RequiredArgsConstructor
@Slf4j
@CrossOrigin(origins = "${app.cors.allowed-origins:*}")
public class JobController {

    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(
            ".3ga", ".8svx", ".aac", ".ac3", ".aif", ".aiff", ".alac", ".amr", ".ape", ".au",
            ".dss", ".flac", ".m4a", ".m4b", ".m4p", ".m4r", ".mp3", ".mpga", ".oga", ".ogg",
            ".mogg", ".opus", ".qcp", ".tta", ".voc", ".wav", ".wma", ".wv");

    private final JobService jobService;
    private final JobQueueService queueService;
    private final ObjectStorageService objectStorageService;

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> uploadAudio(
            @RequestParam("file") MultipartFile file,
            @RequestParam("email") @NotBlank @Email String email) {

        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Archivo vacio"));
        }
        if (!isSupportedByAssemblyAi(file)) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error",
                    "Formato no soportado por AssemblyAI. Usa un formato de audio compatible."));
        }

        try {
            StoredObject storedObject = objectStorageService.uploadAudio(file);

            Job job = Job.builder()
                    .email(email)
                    .status("QUEUED")
                    .audioUrl(storedObject.getPublicUrl())
                    .progressPercent(0)
                    .statusDetail("queued")
                    .build();

            job = jobService.createJob(job);
            queueService.enqueueJob(job.getId());

            Map<String, Object> response = new HashMap<>();
            response.put("jobId", job.getId());
            response.put("status", job.getStatus());
            response.put("message", "Procesamiento encolado. Consulta el estado con el jobId.");
            response.put("checkStatusUrl", "/v1/jobs/" + job.getId() + "/status");

            return ResponseEntity.accepted().body(response);
        } catch (Exception e) {
            log.error("Error creating queued job", e);
            return ResponseEntity.status(500).body(Map.of("error", "No se pudo encolar el trabajo"));
        }
    }

    @GetMapping("/{jobId}/status")
    public ResponseEntity<Map<String, Object>> getJobStatus(@PathVariable Long jobId) {
        Job job = jobService.getJobStatus(jobId);

        if (job == null) {
            return ResponseEntity.notFound().build();
        }

        Map<String, Object> response = new HashMap<>();
        response.put("jobId", job.getId());
        response.put("status", job.getStatus());
        response.put("createdAt", job.getCreatedAt());
        response.put("progressPercent", job.getProgressPercent());
        response.put("statusDetail", job.getStatusDetail());

        if ("COMPLETED".equals(job.getStatus())) {
            response.put("miniSummary", job.getMiniSummary());
            response.put("completedAt", job.getCompletedAt());
            response.put("processingTimeMs", job.getProcessingTimeMs());
        }

        if ("ERROR".equals(job.getStatus())) {
            response.put("error", job.getErrorMessage());
        }

        return ResponseEntity.ok(response);
    }

    @GetMapping("/all")
    public ResponseEntity<?> getAll() {
        var jobs = jobService.getAllJobs();
        return ResponseEntity.ok(jobs);
    }

    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("OK");
    }

    @PostMapping("/webhook")
    public ResponseEntity<String> assemblyAiWebhook(@RequestBody Map<String, Object> payload) {
        try {
            jobService.handleAssemblyAiWebhook(payload);
            return ResponseEntity.ok("received");
        } catch (Exception e) {
            log.error("Error handling webhook", e);
            return ResponseEntity.status(500).body("error");
        }
    }

    private boolean isSupportedByAssemblyAi(MultipartFile file) {
        String filename = file.getOriginalFilename();
        if (filename == null || filename.isBlank()) {
            return false;
        }
        String lower = filename.toLowerCase(Locale.ROOT);
        return ALLOWED_EXTENSIONS.stream().anyMatch(lower::endsWith);
    }
}
