package com.mediasummary.service;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.mediasummary.model.Job;
import com.mediasummary.repository.JobRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

@Service
@RequiredArgsConstructor
@Slf4j
public class JobService {

    private final JobRepository jobRepository;
    private final EmailService emailService;

    @Value("${assemblyai.api.key}")
    private String assemblyAiKey;

    @Value("${assemblyai.poll.attempts:60}")
    private int assemblyAiPollAttempts;

    @Value("${assemblyai.poll.delay.ms:1000}")
    private long assemblyAiPollDelayMs;

    @Value("${assemblyai.poll.backoff.factor:1.5}")
    private double assemblyAiPollBackoffFactor;

    @Value("${assemblyai.speech.models:universal-3-pro}")
    private String assemblyAiSpeechModels;

    @Value("${assemblyai.webhook.url:}")
    private String assemblyAiWebhookUrl;

    @Value("${groq.api.key}")
    private String groqApiKey;

    @Value("${groq.api.url}")
    private String groqApiUrl;

    @Value("${groq.model}")
    private String groqModel;

    @Value("${groq.max-tokens:4000}")
    private int groqMaxTokens;

    @Value("${groq.reasoning-effort:low}")
    private String groqReasoningEffort;

    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build();

    private final Gson gson = new Gson();

    public Job createJob(Job job) {
        if (job.getStatus() == null) job.setStatus("QUEUED");
        if (job.getCreatedAt() == null) job.setCreatedAt(LocalDateTime.now());
        if (job.getProgressPercent() == null) job.setProgressPercent(0);
        if (job.getStatusDetail() == null) job.setStatusDetail("queued");

        Long jobId = jobRepository.save(job);
        job.setId(jobId);
        log.info("Job created: {} for email: {}", jobId, job.getEmail());
        return job;
    }

    public void processJob(Long jobId) {
        long startTime = System.currentTimeMillis();

        try {
            Job existing = jobRepository.findById(jobId);
            if (existing == null) {
                log.warn("Job {} not found in DB", jobId);
                return;
            }

            if ("COMPLETED".equalsIgnoreCase(existing.getStatus())) {
                log.info("Job {} already completed; skipping", jobId);
                return;
            }

            if (existing.getAudioUrl() == null || existing.getAudioUrl().isBlank()) {
                throw new IllegalStateException("Job has no shared audio URL");
            }

            jobRepository.updateStatus(jobId, "PROCESSING", null);
            jobRepository.updateProgress(jobId, 10, "transcribing");

            String transcription = transcribeWithAssemblyAI(jobId, existing.getAudioUrl());
            if (transcription == null || transcription.isBlank()) {
                log.info("Transcription still pending for job {}. Keeping PROCESSING.", jobId);
                return;
            }

            jobRepository.updateProgress(jobId, 75, "summarizing");
            Summaries summaries = generateSummaries(transcription);

            int processingTime = (int) (System.currentTimeMillis() - startTime);
            jobRepository.updateCompleted(jobId, "COMPLETED", summaries.getMiniSummary(), processingTime);
            jobRepository.updateProgress(jobId, 100, "completed");

            Job completedJob = jobRepository.findById(jobId);
            emailService.sendSummaryEmail(completedJob, summaries.getEmailSummary());
            log.info("Job {} completed in {}ms", jobId, processingTime);
        } catch (Exception e) {
            log.error("Error processing job {}", jobId, e);
            jobRepository.updateStatus(jobId, "ERROR", e.getMessage());
            Job failedJob = jobRepository.findById(jobId);
            if (failedJob != null) {
                emailService.sendErrorEmail(failedJob.getEmail(), e.getMessage());
            }
        }
    }

    public Job getJobStatus(Long jobId) {
        return jobRepository.findById(jobId);
    }

    public List<Job> getAllJobs() {
        return jobRepository.getAll();
    }

    private String transcribeWithAssemblyAI(Long jobId, String audioUrl) throws IOException {
        log.info("Job {}: Starting transcription with AssemblyAI", jobId);
        
        JsonObject transcriptReq = new JsonObject();
        transcriptReq.addProperty("audio_url", audioUrl);

        com.google.gson.JsonArray modelsArray = new com.google.gson.JsonArray();
        for (String m : assemblyAiSpeechModels.split(",")) {
            String mm = m.trim();
            if (!mm.isEmpty()) modelsArray.add(mm);
        }
        transcriptReq.add("speech_models", modelsArray);

        if (assemblyAiWebhookUrl != null && !assemblyAiWebhookUrl.isBlank()) {
            transcriptReq.addProperty("webhook_url", assemblyAiWebhookUrl);
        }

        String json = gson.toJson(transcriptReq);
        RequestBody transcriptBody = RequestBody.create(json, MediaType.parse("application/json"));

        Request transcriptRequest = new Request.Builder()
                .url("https://api.assemblyai.com/v2/transcript")
                .header("authorization", assemblyAiKey)
                .header("content-type", "application/json")
                .post(transcriptBody)
                .build();

        try (Response response = client.newCall(transcriptRequest).execute()) {
            String body = response.body() != null ? response.body().string() : null;
            
            if (!response.isSuccessful()) {
                log.error("Job {}: AssemblyAI transcript creation failed. HTTP {}", jobId, response.code());
                throw new IOException("AssemblyAI transcript creation failed: " + response.code() + " body: " + body);
            }
            
            JsonObject obj = gson.fromJson(body, JsonObject.class);
            String transcriptId = obj != null && obj.has("id") ? obj.get("id").getAsString() : null;

            if (transcriptId == null) {
                log.error("Job {}: AssemblyAI response missing transcript ID", jobId);
                throw new IOException("Transcript creation failed: " + body);
            }

            log.info("Job {}: Transcription submitted to AssemblyAI, transcriptId={}", jobId, transcriptId);
            jobRepository.updateAudioAndTranscript(jobId, audioUrl, transcriptId);
            return pollTranscriptionResult(jobId, transcriptId);
        } catch (IOException e) {
            log.error("Job {}: Failed to connect to AssemblyAI - {}", jobId, e.getMessage());
            throw e;
        }
    }

    public void handleAssemblyAiWebhook(java.util.Map<String, Object> payload) {
        try {
            if (payload == null) {
                log.warn("Received empty webhook payload");
                return;
            }

            String transcriptId = payload.get("id") != null ? payload.get("id").toString() : null;
            String status = payload.get("status") != null ? payload.get("status").toString() : null;

            if (transcriptId == null) {
                log.warn("Webhook missing transcript id");
                return;
            }

            Job job = jobRepository.findByTranscriptId(transcriptId);
            if (job == null) {
                log.warn("No job found for transcript id {}", transcriptId);
                return;
            }

            if ("completed".equalsIgnoreCase(status)) {
                String text = payload.get("text") != null ? payload.get("text").toString() : null;
                if (text == null || text.isBlank()) {
                    text = fetchTranscriptText(transcriptId);
                }

                if (text == null || text.isBlank()) {
                    jobRepository.updateStatus(job.getId(), "ERROR", "No transcript text available");
                    return;
                }

                Summaries summaries = generateSummaries(text);
                int processingTime = 0;
                if (job.getCreatedAt() != null) {
                    processingTime = (int) java.time.Duration.between(job.getCreatedAt(), LocalDateTime.now()).toMillis();
                }

                jobRepository.updateCompleted(job.getId(), "COMPLETED", summaries.getMiniSummary(), processingTime);
                jobRepository.updateProgress(job.getId(), 100, "completed");
                emailService.sendSummaryEmail(jobRepository.findById(job.getId()), summaries.getEmailSummary());
            } else if ("error".equalsIgnoreCase(status)) {
                String msg = payload.get("error") != null ? payload.get("error").toString() : "AssemblyAI reported error";
                jobRepository.updateStatus(job.getId(), "ERROR", msg);
                emailService.sendErrorEmail(job.getEmail(), msg);
            } else {
                jobRepository.updateProgress(job.getId(), 30, status != null ? status : "processing");
            }
        } catch (Exception e) {
            log.error("Unhandled exception processing assemblyai webhook", e);
        }
    }

    private String fetchTranscriptText(String transcriptId) {
        try {
            Request req = new Request.Builder()
                    .url("https://api.assemblyai.com/v2/transcript/" + transcriptId)
                    .header("authorization", assemblyAiKey)
                    .build();

            try (Response resp = client.newCall(req).execute()) {
                String body = resp.body() != null ? resp.body().string() : null;
                JsonObject obj = gson.fromJson(body, JsonObject.class);
                if (obj != null && obj.has("text") && !obj.get("text").isJsonNull()) {
                    return obj.get("text").getAsString();
                }
            }
        } catch (Exception e) {
            log.warn("Failed to fetch transcript {}: {}", transcriptId, e.getMessage());
        }
        return null;
    }

    private Summaries generateSummaries(String transcription) throws IOException {
        log.info("Generating summaries with Groq API");
        
        String prompt = "Analiza este texto y genera DOS resumenes en espanol, separados por [SEPARATOR]. " +
                "No uses ingles. Usa un tono profesional y claro.\n\n" +
                "SHORT_SUMMARY: Maximo 50 palabras en espanol, resumen ejecutivo para vista previa.\n" +
                "LONG_SUMMARY: 250-300 palabras en espanol, resumen profesional detallado para email.\n\n" +
                "Texto: " + transcription.substring(0, Math.min(transcription.length(), 3000));

        JsonObject req = new JsonObject();
        req.addProperty("model", groqModel);

        com.google.gson.JsonArray messages = new com.google.gson.JsonArray();
        JsonObject m = new JsonObject();
        m.addProperty("role", "user");
        m.addProperty("content", prompt);
        messages.add(m);
        req.add("messages", messages);

        req.addProperty("temperature", 0.3);
        // A reasoning model spends this budget thinking before it answers and returns an
        // empty content if it runs out first, so the limit has to cover both parts.
        req.addProperty("max_tokens", groqMaxTokens);
        if (groqReasoningEffort != null && !groqReasoningEffort.isBlank()) {
            req.addProperty("reasoning_effort", groqReasoningEffort.trim());
        }

        RequestBody body = RequestBody.create(gson.toJson(req), MediaType.parse("application/json"));
        Request request = new Request.Builder()
                .url(groqApiUrl)
                .header("Authorization", "Bearer " + groqApiKey)
                .header("Content-Type", "application/json")
                .post(body)
                .build();

        try (Response response = client.newCall(request).execute()) {
            String responseBody = response.body() != null ? response.body().string() : null;
            if (!response.isSuccessful()) {
                log.error("Groq API request failed. HTTP {} - {}", response.code(), responseBody);
                throw new IOException("Groq API error: " + response.code() + " body: " + responseBody);
            }
            log.info("Groq API request successful");
            return parseSummaries(responseBody);
        } catch (IOException e) {
            log.error("Failed to connect to Groq API - {}", e.getMessage());
            throw e;
        }
    }

    private static final int MINI_SUMMARY_MAX_CHARS = 500;

    private Summaries parseSummaries(String groqResponse) {
        String content = extractContent(groqResponse);
        List<String> sections = splitSummarySections(content);

        String shortSection;
        String longSection;

        if (sections.size() >= 2) {
            shortSection = sections.get(0);
            longSection = sections.get(1);
        } else if (sections.size() == 1) {
            // The model answered in a single block; use it for both.
            longSection = sections.get(0);
            shortSection = longSection;
        } else {
            longSection = content.trim();
            shortSection = longSection;
        }

        return new Summaries(truncateOnWordBoundary(shortSection), longSection);
    }

    /**
     * The prompt asks for SHORT_SUMMARY, [SEPARATOR] and LONG_SUMMARY, but the model does not
     * always emit all three. Splitting on the labels and the separator at once left an empty
     * gap wherever two of them were adjacent, and that gap was taken as the long summary, so
     * the email arrived with an empty body. Split on the separator, strip the labels, and
     * discard whatever comes out blank.
     */
    private List<String> splitSummarySections(String content) {
        String[] chunks = content.split("\\[SEPARATOR\\]");
        if (chunks.length < 2) {
            // No separator emitted: fall back to the label itself as the boundary.
            chunks = content.split("(?i)LONG_SUMMARY\\s*:");
        }

        List<String> sections = new ArrayList<>();
        for (String chunk : chunks) {
            String cleaned = chunk.replaceAll("(?i)(SHORT_SUMMARY|LONG_SUMMARY)\\s*:", "").trim();
            if (!cleaned.isEmpty()) {
                sections.add(cleaned);
            }
        }
        return sections;
    }

    private String truncateOnWordBoundary(String text) {
        if (text.length() <= MINI_SUMMARY_MAX_CHARS) {
            return text;
        }
        String cut = text.substring(0, MINI_SUMMARY_MAX_CHARS);
        int lastSpace = cut.lastIndexOf(' ');
        if (lastSpace > MINI_SUMMARY_MAX_CHARS / 2) {
            cut = cut.substring(0, lastSpace);
        }
        return cut.trim() + "...";
    }

    private String pollTranscriptionResult(Long jobId, String transcriptId) throws IOException {
        log.info("Job {}: Starting transcription polling for transcriptId={}", jobId, transcriptId);
        long delay = assemblyAiPollDelayMs;
        for (int attempt = 1; attempt <= assemblyAiPollAttempts; attempt++) {
            Request request = new Request.Builder()
                    .url("https://api.assemblyai.com/v2/transcript/" + transcriptId)
                    .header("authorization", assemblyAiKey)
                    .build();

            try (Response response = client.newCall(request).execute()) {
                String body = response.body() != null ? response.body().string() : null;
                if (body != null) {
                    JsonObject obj = gson.fromJson(body, JsonObject.class);
                    String status = obj != null && obj.has("status") && !obj.get("status").isJsonNull()
                            ? obj.get("status").getAsString()
                            : null;

                    if ("completed".equalsIgnoreCase(status)) {
                        log.info("Job {}: AssemblyAI transcription completed", jobId);
                        Job j = jobRepository.findByTranscriptId(transcriptId);
                        if (j != null) jobRepository.updateProgress(j.getId(), 100, "completed");
                        return parseTranscriptionText(body);
                    }

                    if ("error".equalsIgnoreCase(status)) {
                        String err = obj.has("error") && !obj.get("error").isJsonNull()
                                ? obj.get("error").getAsString()
                                : "AssemblyAI reported error";
                        log.error("Job {}: AssemblyAI transcription failed - {}", jobId, err);
                        throw new IOException("Transcription failed: " + err);
                    }

                    if ("queued".equalsIgnoreCase(status) || "processing".equalsIgnoreCase(status)) {
                        log.debug("Job {}: AssemblyAI status={} (attempt {}/{})", jobId, status, attempt, assemblyAiPollAttempts);
                        int approx = Math.min(99, Math.max(1, (int) ((attempt / (double) assemblyAiPollAttempts) * 100)));
                        Job j = jobRepository.findByTranscriptId(transcriptId);
                        if (j != null) jobRepository.updateProgress(j.getId(), approx, status);
                    }
                }
            } catch (IOException e) {
                log.warn("Job {}: Error polling AssemblyAI (attempt {}/{}) - {}", jobId, attempt, assemblyAiPollAttempts, e.getMessage());
            }

            if (attempt == assemblyAiPollAttempts) break;

            try {
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Transcription polling interrupted", e);
            }
            delay = Math.min((long) (delay * assemblyAiPollBackoffFactor), 30000L);
        }

        log.error("Job {}: Transcription polling timeout after {} attempts", jobId, assemblyAiPollAttempts);
        return null;
    }

    private String parseTranscriptionText(String json) {
        try {
            JsonObject obj = gson.fromJson(json, JsonObject.class);
            return obj.has("text") ? obj.get("text").getAsString() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Gson handles quotes and unicode escapes inside the content; manual scanning did not. */
    private String extractContent(String json) {
        try {
            JsonObject obj = gson.fromJson(json, JsonObject.class);
            JsonObject choice = obj.getAsJsonArray("choices").get(0).getAsJsonObject();
            com.google.gson.JsonElement content = choice.getAsJsonObject("message").get("content");

            String text = content == null || content.isJsonNull() ? "" : content.getAsString();

            if (text.isBlank()) {
                // A successful call that produced nothing is otherwise invisible: the request
                // returns 200 and the summary silently comes out empty. A reasoning model that
                // exhausts its budget before answering looks exactly like this.
                log.error("Model returned no content (finish_reason={}, {}). "
                        + "Raise GROQ_MAX_TOKENS or lower GROQ_REASONING_EFFORT.",
                        readMember(choice, "finish_reason"), describeUsage(obj));
            }

            return text;
        } catch (Exception e) {
            log.error("Could not read the model response content: {}", e.getMessage());
            return "";
        }
    }

    private String describeUsage(JsonObject root) {
        JsonObject usage = root.getAsJsonObject("usage");
        if (usage == null) {
            return "usage unavailable";
        }
        return "completion_tokens=" + readMember(usage, "completion_tokens")
                + " total_tokens=" + readMember(usage, "total_tokens");
    }

    private String readMember(JsonObject object, String member) {
        com.google.gson.JsonElement value = object == null ? null : object.get(member);
        return value == null || value.isJsonNull() ? "unknown" : value.getAsString();
    }

    @lombok.AllArgsConstructor
    @lombok.Getter
    private static class Summaries {
        private final String miniSummary;
        private final String emailSummary;
    }
}
