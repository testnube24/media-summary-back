package com.mediasummary.service;

import java.io.IOException;
import java.time.LocalDate;
import java.util.Locale;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

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

    @Override
    public StoredObject uploadAudio(MultipartFile file) {
        try {
            if (file == null || file.isEmpty()) {
                throw new IllegalArgumentException("File is empty");
            }

            String objectPath = buildObjectPath(file.getOriginalFilename());
            String uploadUrl = String.format("%s/storage/v1/object/%s/%s", trimTrailingSlash(supabaseUrl), bucket, objectPath);

            byte[] bytes = file.getBytes();
            String contentType = file.getContentType();
            if (contentType == null || contentType.isBlank()) {
                contentType = "application/octet-stream";
            }

            Request request = new Request.Builder()
                    .url(uploadUrl)
                    .header("Authorization", "Bearer " + supabaseServiceKey)
                    .header("apikey", supabaseServiceKey)
                    .header("x-upsert", "true")
                    .put(RequestBody.create(bytes, MediaType.parse(contentType)))
                    .build();

            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    String body = response.body() != null ? response.body().string() : "";
                    throw new IOException("Supabase upload failed: " + response.code() + " - " + body);
                }
            }

            String publicUrl = buildPublicUrl(objectPath);
            log.info("Uploaded object to Supabase Storage path={} url={}", objectPath, publicUrl);
            return new StoredObject(objectPath, publicUrl);
        } catch (Exception e) {
            log.error("Error uploading file to Supabase Storage", e);
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
}

