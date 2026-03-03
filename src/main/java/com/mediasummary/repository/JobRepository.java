package com.mediasummary.repository;

import java.sql.Timestamp;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;
import org.springframework.stereotype.Repository;

import com.mediasummary.model.Job;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Repository
@RequiredArgsConstructor
@Slf4j
public class JobRepository {

    private final JdbcTemplate jdbcTemplate;

    private final RowMapper<Job> jobMapper = (rs, rowNum) -> Job.builder()
            .id(rs.getLong("id"))
            .email(rs.getString("email"))
            .status(rs.getString("status"))
            .audioUrl(rs.getString("audio_url"))
            .transcriptId(rs.getString("transcript_id"))
            .miniSummary(rs.getString("mini_summary"))
            .progressPercent(rs.getObject("progress_percent") != null ? rs.getInt("progress_percent") : null)
            .statusDetail(rs.getString("status_detail"))
            .createdAt(rs.getTimestamp("created_at") != null ? rs.getTimestamp("created_at").toLocalDateTime() : null)
            .completedAt(rs.getTimestamp("completed_at") != null ? rs.getTimestamp("completed_at").toLocalDateTime() : null)
            .processingTimeMs(rs.getObject("processing_time_ms") != null ? rs.getInt("processing_time_ms") : null)
            .errorMessage(rs.getString("error_message"))
            .build();

    public Long save(Job job) {
    	try {
    		 // 1. Configuramos el insert (esto se puede instanciar una sola vez en el constructor)
            SimpleJdbcInsert jdbcInsert = new SimpleJdbcInsert(jdbcTemplate)
                    .withTableName("processing_job")
                    .usingGeneratedKeyColumns("id");

            // 2. Creamos el mapa con los valores dinámicos
            Map<String, Object> params = new HashMap<>();
            
            // Campos obligatorios o con default manejado en Java
            params.put("email", job.getEmail());
            params.put("status", job.getStatus() != null ? job.getStatus() : "PENDING");

            // Agregamos campos solo si no son nulos (equivalente a tu lógica previa)
            if (job.getMiniSummary() != null) {
                params.put("mini_summary", job.getMiniSummary());
            }
            if (job.getAudioUrl() != null) {
                params.put("audio_url", job.getAudioUrl());
            }

            if (job.getTranscriptId() != null) {
                params.put("transcript_id", job.getTranscriptId());
            }

            if (job.getProgressPercent() != null) {
                params.put("progress_percent", job.getProgressPercent());
            }

            if (job.getStatusDetail() != null) {
                params.put("status_detail", job.getStatusDetail());
            }
            
            if (job.getCreatedAt() != null) {
                params.put("created_at", Timestamp.valueOf(job.getCreatedAt()));
            }
            
            if (job.getCompletedAt() != null) {
                params.put("completed_at", Timestamp.valueOf(job.getCompletedAt()));
            }
            
            if (job.getProcessingTimeMs() != null) {
                params.put("processing_time_ms", job.getProcessingTimeMs());
            }
            
            if (job.getErrorMessage() != null) {
                params.put("error_message", job.getErrorMessage());
            }

            // 3. Ejecutamos y recuperamos la llave generada automáticamente
            Number newId = jdbcInsert.executeAndReturnKey(params);
            
            return newId.longValue();
    	} catch (DuplicateKeyException e) {
            log.error("Error: El email {} ya tiene un proceso activo o duplicado.", job.getEmail());
            throw new RuntimeException("Ya existe un registro con este identificador único.");

        } catch (DataIntegrityViolationException e) {
            log.error("Error de integridad: Revisa que los campos obligatorios no estén nulos.");
            throw new RuntimeException("Datos inválidos para la base de datos.");

        } catch (Exception e) {
            log.error("Error inesperado al guardar el Job: ", e);
            throw new RuntimeException("Error interno al procesar la solicitud.");
        }
    	
       
    }

    public Job findByTranscriptId(String transcriptId) {
        String sql = "SELECT * FROM processing_job WHERE transcript_id = ?";
        List<Job> jobs = jdbcTemplate.query(sql, jobMapper, transcriptId);
        return jobs.isEmpty() ? null : jobs.get(0);
    }

    public void updateAudioAndTranscript(Long id, String audioUrl, String transcriptId) {
        String sql = "UPDATE processing_job SET audio_url = ?, transcript_id = ? WHERE id = ?";
        jdbcTemplate.update(sql, audioUrl, transcriptId, id);
    }

    public void updateProgress(Long id, Integer progressPercent, String statusDetail) {
        String sql = "UPDATE processing_job SET progress_percent = ?, status_detail = ? WHERE id = ?";
        jdbcTemplate.update(sql, progressPercent, statusDetail, id);
    }

    public Job findById(Long id) {
        String sql = "SELECT * FROM processing_job WHERE id = ?";
        List<Job> jobs = jdbcTemplate.query(sql, jobMapper, id);
        return jobs.isEmpty() ? null : jobs.get(0);
    }

    public void updateStatus(Long id, String status, String errorMessage) {
        String sql = "UPDATE processing_job SET status = ?, error_message = ? WHERE id = ?";
        jdbcTemplate.update(sql, status, errorMessage, id);
    }

    public void updateCompleted(Long id, String status, String miniSummary, int processingTimeMs) {
        String sql = "UPDATE processing_job SET status = ?, mini_summary = ?, processing_time_ms = ?, completed_at = ? WHERE id = ?";
        jdbcTemplate.update(sql,
                status,
                miniSummary,
                processingTimeMs,
                Timestamp.valueOf(java.time.LocalDateTime.now()),
                id);
    }

    public List<Job> findByEmail(String email, int limit) {
        String sql = "SELECT * FROM processing_job WHERE email = ? ORDER BY created_at DESC LIMIT ?";
        return jdbcTemplate.query(sql, jobMapper, email, limit);
    }
    
    public List<Job> getAll() {
        String sql = "SELECT * FROM processing_job";
        List<Job> jobs = jdbcTemplate.query(sql, jobMapper);
        return jobs.isEmpty() ? null : jobs;
    }
}