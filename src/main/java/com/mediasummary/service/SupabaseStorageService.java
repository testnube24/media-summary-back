package com.mediasummary.service;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okio.BufferedSink;
import okio.Okio;
import okio.Source;

@Service
@Slf4j
public class SupabaseStorageService implements ObjectStorageService {

    private final OkHttpClient client = new OkHttpClient();

    @Value("${supabase.url}")
    private String supabaseUrl;

    @Value("${supabase.service.key}")
    private String supabaseServiceKey;

    @Value("${supabase.storage.bucket:audio-files}")
    private String bucket;

    @Value("${supabase.storage.public.base-url:}")
    private String publicBaseUrl;

    @Value("${app.cleanup.days:7}")
    private int cleanupDays;

    @Override
    public StoredObject uploadAudio(MultipartFile file) {
        try {
            if (file == null || file.isEmpty()) {
                throw new IllegalArgumentException("File is empty");
            }

            String objectPath = buildObjectPath(file.getOriginalFilename());
            String uploadUrl = String.format("%s/storage/v1/object/%s/%s", trimTrailingSlash(supabaseUrl), bucket, objectPath);

            String detected = file.getContentType();
            final String contentType = detected == null || detected.isBlank()
                    ? "application/octet-stream"
                    : detected;

            // Stream straight from the multipart temp file. Reading it into a byte[] first
            // costs about twice the file size in heap while the buffer grows, which is more
            // than the container has for anything near the configured size limit.
            RequestBody payload = new RequestBody() {
                @Override
                public MediaType contentType() {
                    return MediaType.parse(contentType);
                }

                @Override
                public long contentLength() {
                    return file.getSize();
                }

                @Override
                public void writeTo(BufferedSink sink) throws IOException {
                    try (InputStream in = file.getInputStream(); Source source = Okio.source(in)) {
                        sink.writeAll(source);
                    }
                }
            };

            Request request = new Request.Builder()
                    .url(uploadUrl)
                    .header("Authorization", "Bearer " + supabaseServiceKey)
                    .header("apikey", supabaseServiceKey)
                    .header("x-upsert", "true")
                    .put(payload)
                    .build();

            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    String body = response.body() != null ? response.body().string() : "";
                    log.error("Supabase upload failed. HTTP {} - {}", response.code(), body);
                    throw new IOException("Supabase upload failed: " + response.code() + " - " + body);
                }
            }

            String publicUrl = buildPublicUrl(objectPath);
            log.info("Successfully uploaded file to Supabase. path={}", objectPath);
            return new StoredObject(objectPath, publicUrl);
        } catch (Exception e) {
            log.error("Failed to upload file to Supabase Storage - {}", e.getMessage());
            throw new RuntimeException("Could not upload file to shared storage", e);
        }
    }

    private String buildObjectPath(String originalFilename) {
        String suffix = "";
        if (originalFilename != null && originalFilename.contains(".")) {
            suffix = originalFilename.substring(originalFilename.lastIndexOf('.')).toLowerCase(Locale.ROOT);
        }
        String day = LocalDate.now().toString();
        return String.format("uploads/%s/%s%s", day, UUID.randomUUID(), suffix);
    }

    private String buildPublicUrl(String objectPath) {
        if (publicBaseUrl != null && !publicBaseUrl.isBlank()) {
            return String.format("%s/%s/%s", trimTrailingSlash(publicBaseUrl), bucket, objectPath);
        }
        return String.format("%s/storage/v1/object/public/%s/%s", trimTrailingSlash(supabaseUrl), bucket, objectPath);
    }

    private String trimTrailingSlash(String value) {
        if (value == null) return "";
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    @Scheduled(cron = "${app.cleanup.cron:0 0 3 * * ?}")
    public void cleanupOldFiles() {
        log.info("Starting cleanup of files older than {} days", cleanupDays);
        
        LocalDate cutoffDate = LocalDate.now().minusDays(cleanupDays);
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");
        
        int deletedCount = 0;
        
        for (int i = 0; i < cleanupDays; i++) {
            LocalDate dateToCheck = cutoffDate.minusDays(i);
            String dateStr = dateToCheck.format(formatter);
            
            try {
                String listUrl = String.format("%s/storage/v1/object/list/%s", 
                    trimTrailingSlash(supabaseUrl), bucket);
                
                String jsonBody = String.format("{\"prefix\":\"uploads/%s/\"}", dateStr);
                
                Request listRequest = new Request.Builder()
                        .url(listUrl)
                        .header("Authorization", "Bearer " + supabaseServiceKey)
                        .header("apikey", supabaseServiceKey)
                        .post(RequestBody.create(jsonBody, MediaType.parse("application/json")))
                        .build();
                
                try (Response listResponse = client.newCall(listRequest).execute()) {
                    if (listResponse.isSuccessful() && listResponse.body() != null) {
                        String responseBody = listResponse.body().string();
                        
                        if (responseBody.contains("\"name\"")) {
                            String[] names = responseBody.split("\"name\":\"");
                            for (int j = 1; j < names.length; j++) {
                                String namePart = names[j];
                                String objectName = namePart.split("\"")[0];
                                String objectPath = "uploads/" + dateStr + "/" + objectName;
                                
                                if (deleteObject(objectPath)) {
                                    deletedCount++;
                                }
                            }
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("Error checking/cleaning files for date {}: {}", dateStr, e.getMessage());
            }
        }
        
        log.info("Cleanup completed. Deleted {} files older than {} days", deletedCount, cleanupDays);
    }

    private boolean deleteObject(String objectPath) {
        try {
            String deleteUrl = String.format("%s/storage/v1/object/%s/%s", 
                trimTrailingSlash(supabaseUrl), bucket, objectPath);
            
            Request deleteRequest = new Request.Builder()
                    .url(deleteUrl)
                    .header("Authorization", "Bearer " + supabaseServiceKey)
                    .header("apikey", supabaseServiceKey)
                    .delete()
                    .build();
            
            try (Response response = client.newCall(deleteRequest).execute()) {
                if (response.isSuccessful()) {
                    log.info("Deleted old file: {}", objectPath);
                    return true;
                } else {
                    log.warn("Failed to delete {}: {}", objectPath, response.code());
                }
            }
        } catch (Exception e) {
            log.warn("Error deleting object {}: {}", objectPath, e.getMessage());
        }
        return false;
    }
}

