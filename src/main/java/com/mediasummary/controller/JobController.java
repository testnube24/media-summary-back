package com.mediasummary.controller;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.servlet.http.HttpServletRequest;
import javax.validation.constraints.Email;
import javax.validation.constraints.NotBlank;

import org.springframework.beans.factory.annotation.Value;
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
import com.mediasummary.service.UploadRateLimiter;

import com.zaxxer.hikari.HikariDataSource;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.jdbc.core.JdbcTemplate;

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
    private final JdbcTemplate jdbcTemplate;
    private final UploadRateLimiter rateLimiter;

    @Value("${app.max-file-size:52428800}")
    private long maxFileSizeBytes;

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> uploadAudio(
            @RequestParam("file") MultipartFile file,
            @RequestParam("email") @NotBlank @Email String email,
            HttpServletRequest request) {

        String clientIp = resolveClientIp(request);
        UploadRateLimiter.Decision decision = rateLimiter.check(clientIp);
        if (!decision.isAllowed()) {
            long minutes = Math.max(1, decision.getRetryAfterSeconds() / 60);
            log.warn("Upload rate limit exceeded for ip={}, email={}", clientIp, email);
            return ResponseEntity.status(429)
                    .header("Retry-After", String.valueOf(decision.getRetryAfterSeconds()))
                    .body(Map.of("error", "Demasiadas solicitudes desde esta conexion. Intenta nuevamente en " + minutes + " minutos."));
        }

        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Archivo vacio"));
        }

        if (file.getSize() > maxFileSizeBytes) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error",
                    "Archivo demasiado grande. Máximo " + (maxFileSizeBytes / (1024 * 1024)) + "MB permitidos."));
        }

        if (!isSupportedByAssemblyAi(file)) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error",
                    "Formato no soportado por AssemblyAI. Usa un formato de audio compatible."));
        }

        try {
            log.info("Received upload request. File: {}, Size: {} bytes, Email: {}", 
                file.getOriginalFilename(), file.getSize(), email);
            
            StoredObject storedObject = objectStorageService.uploadAudio(file);
            log.info("File uploaded to storage. Public URL: {}", storedObject.getPublicUrl());

            Job job = Job.builder()
                    .email(email)
                    .status("QUEUED")
                    .audioUrl(storedObject.getPublicUrl())
                    .progressPercent(0)
                    .statusDetail("queued")
                    .build();

            job = jobService.createJob(job);
            log.info("Job {} created in database", job.getId());

            queueService.enqueueJob(job.getId());
            log.info("Job {} enqueued to Redis", job.getId());

            Map<String, Object> response = new HashMap<>();
            response.put("jobId", job.getId());
            response.put("status", job.getStatus());
            response.put("message", "Procesamiento encolado. Consulta el estado con el jobId.");
            response.put("checkStatusUrl", "/v1/jobs/" + job.getId() + "/status");

            return ResponseEntity.accepted().body(response);
        } catch (Exception e) {
            log.error("Error creating queued job. File: {}, Email: {}, Error: {}", 
                file != null ? file.getOriginalFilename() : "null",
                email, e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of("error", "No se pudo encolar el trabajo: " + e.getMessage()));
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

    @GetMapping("/pool")
    public ResponseEntity<?> poolStatus() {
        var ds = jdbcTemplate.getDataSource();
        if (ds instanceof HikariDataSource) {
            HikariDataSource hikari = (HikariDataSource) ds;
            var config = hikari.getHikariConfigMXBean();
            var metrics = hikari.getHikariPoolMXBean();
            Map<String, Object> result = new HashMap<>();
            result.put("poolName", hikari.getPoolName());
            result.put("maximumPoolSize", config.getMaximumPoolSize());
            result.put("minimumIdle", config.getMinimumIdle());
            result.put("activeConnections", metrics.getActiveConnections());
            result.put("idleConnections", metrics.getIdleConnections());
            result.put("totalConnections", metrics.getTotalConnections());
            result.put("threadsAwaitingConnection", metrics.getThreadsAwaitingConnection());
            return ResponseEntity.ok(result);
        }
        return ResponseEntity.ok("Not using HikariCP");
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

    /** Render sits behind a proxy, so the real client is the first hop in X-Forwarded-For. */
    private String resolveClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            String first = forwarded.split(",")[0].trim();
            if (!first.isEmpty()) {
                return first;
            }
        }
        return request.getRemoteAddr();
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
