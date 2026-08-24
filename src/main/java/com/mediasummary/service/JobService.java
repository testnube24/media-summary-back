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
import com.mediasummary.model.SpeakerSummary;
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

    @Value("${assemblyai.speaker-labels:true}")
    private boolean assemblyAiSpeakerLabels;

    @Value("${groq.api.key}")
    private String groqApiKey;

    @Value("${groq.api.url}")
    private String groqApiUrl;

    @Value("${groq.model}")
    private String groqModel;

    @Value("${groq.max-tokens:3000}")
    private int groqMaxTokens;

    @Value("${groq.reasoning-effort:low}")
    private String groqReasoningEffort;

    private static final int GROQ_SIZE_RETRIES = 3;

    @Value("${app.transcript-log-chars:400}")
    private int transcriptLogChars;

    @Value("${groq.transcript-chars:14000}")
    private int groqTranscriptChars;

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

            logTranscriptSample(jobId, transcription);

            jobRepository.updateProgress(jobId, 75, "summarizing");
            Summaries summaries = generateSummaries(transcription);

            int processingTime = (int) (System.currentTimeMillis() - startTime);
            jobRepository.updateCompleted(jobId, "COMPLETED", summaries.getMiniSummary(), processingTime);
            jobRepository.updateProgress(jobId, 100, "completed");

            Job completedJob = jobRepository.findById(jobId);
            emailService.sendSummaryEmail(completedJob, summaries.getEmailSummary(), summaries.getSpeakers());
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

        if (assemblyAiSpeakerLabels) {
            // Turns the flat text into an utterances array tagged by speaker.
            transcriptReq.addProperty("speaker_labels", true);
        }

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
                emailService.sendSummaryEmail(jobRepository.findById(job.getId()),
                        summaries.getEmailSummary(), summaries.getSpeakers());
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
                String transcript = readTranscript(obj);
                if (transcript != null && !transcript.isBlank()) {
                    return transcript;
                }
            }
        } catch (Exception e) {
            log.warn("Failed to fetch transcript {}: {}", transcriptId, e.getMessage());
        }
        return null;
    }

    /**
     * The free tier caps tokens per minute, and both the transcript and max_tokens count
     * against it, so a long enough audio is rejected outright with a 413. Halve the transcript
     * and try again rather than failing a job that already paid for its transcription.
     */
    private Summaries generateSummaries(String transcription) throws IOException {
        int chars = groqTranscriptChars;

        for (int attempt = 1; ; attempt++) {
            try {
                return requestSummaries(transcription, chars);
            } catch (RequestTooLargeException e) {
                if (attempt >= GROQ_SIZE_RETRIES) {
                    throw new IOException("Groq rejected the request as too large after "
                            + attempt + " attempts: " + e.getMessage(), e);
                }
                chars = Math.max(1000, chars / 2);
                log.warn("Groq rejected the request as too large, retrying with {} characters", chars);
            }
        }
    }

    private Summaries requestSummaries(String transcription, int maxChars) throws IOException {
        log.info("Generating summaries with Groq API");

        String prompt = "Analiza esta transcripcion y responde en espanol, nunca en ingles, "
                + "con un tono profesional y claro.\n\n"
                + "mini_summary: maximo 50 palabras, resumen ejecutivo para vista previa.\n"
                + "full_summary: 250-300 palabras, resumen detallado para el correo.\n"
                + "speakers: un elemento por participante, con lo que aporto cada uno.\n"
                + "Cada linea de la transcripcion empieza con la voz que habla entre corchetes: "
                + "[A], [B], [C]. Son etiquetas anonimas, no nombres.\n"
                + "Antes de responder, averigua a que persona corresponde cada letra. Las pistas "
                + "estan en lo que se dice: alguien se presenta ('soy Juan'), lo saludan o lo "
                + "interpelan ('Juan, que opinas?') y responde en la linea siguiente, o le "
                + "agradecen despues de que hablo ('gracias Juan'). Cruza esas menciones con el "
                + "turno de palabra para saber quien es quien.\n"
                + "Que un nombre se mencione no significa que esa persona este hablando: en una "
                + "reunion se habla de gente ausente. Asigna un nombre a una letra solo si puedes "
                + "ligarlo a un turno concreto de esa letra.\n"
                + "En el campo 'speaker' pon el nombre de la persona si lograste identificarlo. "
                + "Si no, pon 'Participante ' seguido de su letra, por ejemplo 'Participante A'.\n"
                + "Nunca inventes un nombre ni lo deduzcas del tema de conversacion: es preferible "
                + "la etiqueta anonima antes que un nombre equivocado.\n"
                + "Si solo hay una persona, devuelve un unico elemento. Si no se distinguen "
                + "participantes, devuelve la lista vacia.\n\n"
                + "Transcripcion:\n" + truncateTranscript(transcription, maxChars);

        JsonObject req = new JsonObject();
        req.addProperty("model", groqModel);
        // Constrained decoding: the model cannot answer outside this shape, which removes the
        // guesswork of splitting free-form text into sections.
        req.add("response_format", summaryResponseFormat());

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
                if (isRequestTooLarge(response.code(), responseBody)) {
                    // Expected for a long transcript, and the caller shrinks and tries again.
                    log.warn("Groq rejected the request size. HTTP {}", response.code());
                    throw new RequestTooLargeException(responseBody);
                }
                log.error("Groq API request failed. HTTP {} - {}", response.code(), responseBody);
                throw new IOException("Groq API error: " + response.code() + " body: " + responseBody);
            }
            log.info("Groq API request successful");
            return parseSummaries(responseBody);
        } catch (RequestTooLargeException e) {
            throw e;
        } catch (IOException e) {
            log.error("Failed to connect to Groq API - {}", e.getMessage());
            throw e;
        }
    }

    /**
     * A 413 here means the request alone exceeds the per-minute token allowance, which shrinking
     * the transcript can fix. A 429 means the allowance is spent for now, which it cannot.
     */
    private boolean isRequestTooLarge(int statusCode, String body) {
        return statusCode == 413 || (body != null && body.contains("Request too large"));
    }

    /**
     * Whether a participant can be named at all depends on someone saying their name, which is
     * only visible in the transcript. Logging the opening lines makes the difference between
     * "the model ignored the instruction" and "nobody introduced themselves" observable.
     *
     * This puts conversation content in the logs, so set the limit to 0 to turn it off.
     */
    private void logTranscriptSample(Long jobId, String transcription) {
        if (transcriptLogChars <= 0 || transcription == null || transcription.isBlank()) {
            return;
        }
        String sample = transcription.length() <= transcriptLogChars
                ? transcription
                : transcription.substring(0, transcriptLogChars) + "...";
        log.info("Job {}: transcript starts with: {}", jobId, sample.replace('\n', '|'));
    }

    private String truncateTranscript(String transcription, int maxChars) {
        if (transcription == null) {
            return "";
        }
        if (transcription.length() <= maxChars) {
            return transcription;
        }
        log.warn("Transcript is {} characters, only the first {} are summarized",
                transcription.length(), maxChars);
        return transcription.substring(0, maxChars);
    }

    /** Signals that the request has to shrink, as opposed to a plain API failure. */
    private static class RequestTooLargeException extends IOException {
        private static final long serialVersionUID = 1L;

        RequestTooLargeException(String message) {
            super(message);
        }
    }

    /** JSON schema for the answer. Strict mode requires every field listed and no extras. */
    private JsonObject summaryResponseFormat() {
        JsonObject stringField = new JsonObject();
        stringField.addProperty("type", "string");

        JsonObject speakerProps = new JsonObject();
        speakerProps.add("speaker", stringField.deepCopy());
        speakerProps.add("summary", stringField.deepCopy());

        com.google.gson.JsonArray speakerRequired = new com.google.gson.JsonArray();
        speakerRequired.add("speaker");
        speakerRequired.add("summary");

        JsonObject speakerItem = new JsonObject();
        speakerItem.addProperty("type", "object");
        speakerItem.add("properties", speakerProps);
        speakerItem.add("required", speakerRequired);
        speakerItem.addProperty("additionalProperties", false);

        JsonObject speakers = new JsonObject();
        speakers.addProperty("type", "array");
        speakers.add("items", speakerItem);

        JsonObject properties = new JsonObject();
        properties.add("mini_summary", stringField.deepCopy());
        properties.add("full_summary", stringField.deepCopy());
        properties.add("speakers", speakers);

        com.google.gson.JsonArray required = new com.google.gson.JsonArray();
        required.add("mini_summary");
        required.add("full_summary");
        required.add("speakers");

        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", properties);
        schema.add("required", required);
        schema.addProperty("additionalProperties", false);

        JsonObject jsonSchema = new JsonObject();
        jsonSchema.addProperty("name", "media_summary");
        jsonSchema.addProperty("strict", true);
        jsonSchema.add("schema", schema);

        JsonObject responseFormat = new JsonObject();
        responseFormat.addProperty("type", "json_schema");
        responseFormat.add("json_schema", jsonSchema);
        return responseFormat;
    }

    private static final int MINI_SUMMARY_MAX_CHARS = 500;

    /**
     * The answer is constrained to a JSON schema, so it is read as data rather than pulled
     * apart with delimiters. Anything unexpected degrades to the raw content instead of
     * throwing, since a job that reaches this point already paid for transcription.
     */
    private Summaries parseSummaries(String groqResponse) {
        String content = extractContent(groqResponse);
        if (content.isBlank()) {
            return new Summaries("", "", new ArrayList<>());
        }

        try {
            JsonObject answer = gson.fromJson(content, JsonObject.class);

            String mini = readText(answer, "mini_summary");
            String full = readText(answer, "full_summary");
            List<SpeakerSummary> speakers = readSpeakers(answer);

            if (full.isBlank()) {
                full = mini;
            }
            if (mini.isBlank()) {
                mini = full;
            }

            return new Summaries(truncateOnWordBoundary(mini), full, speakers);
        } catch (Exception e) {
            log.warn("Model answer was not the expected JSON ({}), using it as plain text", e.getMessage());
            return new Summaries(truncateOnWordBoundary(content.trim()), content.trim(), new ArrayList<>());
        }
    }

    private List<SpeakerSummary> readSpeakers(JsonObject answer) {
        List<SpeakerSummary> speakers = new ArrayList<>();
        com.google.gson.JsonArray array = answer.getAsJsonArray("speakers");
        if (array == null) {
            return speakers;
        }

        for (com.google.gson.JsonElement element : array) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject speaker = element.getAsJsonObject();
            String name = readText(speaker, "speaker");
            String summary = readText(speaker, "summary");
            if (!name.isBlank() && !summary.isBlank()) {
                speakers.add(new SpeakerSummary(name, summary));
            }
        }
        return speakers;
    }

    private String readText(JsonObject object, String member) {
        com.google.gson.JsonElement value = object == null ? null : object.get(member);
        return value == null || value.isJsonNull() ? "" : value.getAsString().trim();
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
            return readTranscript(obj);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Prefers the diarized utterances so the model can tell the participants apart. Falls back
     * to the flat text when diarization is off or the audio has a single speaker.
     */
    private String readTranscript(JsonObject transcript) {
        if (transcript == null) {
            return null;
        }

        com.google.gson.JsonArray utterances = transcript.getAsJsonArray("utterances");
        if (utterances != null && utterances.size() > 0) {
            StringBuilder sb = new StringBuilder();
            for (com.google.gson.JsonElement element : utterances) {
                JsonObject utterance = element.getAsJsonObject();
                String speaker = utterance.has("speaker") && !utterance.get("speaker").isJsonNull()
                        ? utterance.get("speaker").getAsString()
                        : "?";
                String text = utterance.has("text") && !utterance.get("text").isJsonNull()
                        ? utterance.get("text").getAsString()
                        : "";
                if (!text.isBlank()) {
                    // Just the letter: writing "Participante A" here made the model echo that
                    // label back instead of looking for the person's actual name.
                    sb.append('[').append(speaker).append("] ").append(text).append('\n');
                }
            }
            if (sb.length() > 0) {
                return sb.toString().trim();
            }
        }

        return transcript.has("text") && !transcript.get("text").isJsonNull()
                ? transcript.get("text").getAsString()
                : null;
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
        private final List<SpeakerSummary> speakers;
    }
}
