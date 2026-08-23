package com.mediasummary.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import com.mediasummary.model.Job;

import javax.mail.MessagingException;
import javax.mail.internet.MimeMessage;
import java.time.format.DateTimeFormatter;

@Service
@RequiredArgsConstructor
@Slf4j
public class EmailService {

    private final JavaMailSender mailSender;

    // Relays like Brevo/SendGrid use an API identifier as the SMTP username,
    // which is not a valid From address. Default keeps Gmail working as-is.
    @Value("${app.mail.from:${spring.mail.username}}")
    private String fromEmail;

    public void sendSummaryEmail(Job job, String emailSummary) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");

            helper.setTo(job.getEmail());
            helper.setFrom(fromEmail);
            helper.setSubject("📄 Resumen Profesional - Procesamiento #" + job.getId());

            if (emailSummary == null || emailSummary.isBlank()) {
                // Better to notice a hollow email in the logs than to have users receive one.
                log.warn("Job {}: summary body is empty, the email will have no content", job.getId());
            }

            String html = buildEmailTemplate(job, emailSummary);
            helper.setText(html, true);

            mailSender.send(message);
            log.info("Job {}: Summary email sent successfully to {}", job.getId(), job.getEmail());

        } catch (Exception e) {
            log.error("Job {}: Failed to send summary email to {} - {}", job.getId(), job.getEmail(), e.getMessage());
        }
    }

    public void sendErrorEmail(String email, String errorMessage) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");

            helper.setTo(email);
            helper.setFrom(fromEmail);
            helper.setSubject("❌ Error en procesamiento");

            String html = "<h2>Error en procesamiento</h2>" +
                    "<p>Lo sentimos, hubo un error: " + errorMessage + "</p>" +
                    "<p>Por favor intenta nuevamente.</p>";

            helper.setText(html, true);
            mailSender.send(message);
            log.info("Error email sent to {}", email);

        } catch (Exception e) {
            log.error("Failed to send error email to {} - {}", email, e.getMessage());
        }
    }

    private String buildEmailTemplate(Job job, String emailSummary) {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

        return "<!DOCTYPE html>" +
                "<html>" +
                "<head>" +
                "<style>" +
                "body { font-family: Arial, sans-serif; max-width: 600px; margin: 0 auto; color: #333; }" +
                ".header { background: linear-gradient(135deg, #667eea 0%, #764ba2 100%); color: white; padding: 30px; text-align: center; }" +
                ".content { background: #f9f9f9; padding: 30px; }" +
                ".summary-box { background: white; padding: 20px; border-left: 4px solid #667eea; margin: 20px 0; }" +
                ".footer { text-align: center; color: #666; font-size: 12px; margin-top: 30px; }" +
                "</style>" +
                "</head>" +
                "<body>" +
                "<div class='header'>" +
                "<h1>🎯 Resumen Profesional</h1>" +
                "<p>Job #" + job.getId() + "</p>" +
                "</div>" +
                "<div class='content'>" +
                "<div class='summary-box'>" +
                "<h2>📋 Resumen Ejecutivo</h2>" +
                "<p>" + emailSummary.replace("\n", "<br>") + "</p>" +
                "</div>" +
                "<div class='footer'>" +
                "<p>Procesado en " + (job.getProcessingTimeMs() / 1000) + " segundos</p>" +
                "<p>© Audio Summary App</p>" +
                "</div>" +
                "</div>" +
                "</body>" +
                "</html>";
    }
}