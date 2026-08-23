package com.mediasummary.config;

import javax.annotation.PostConstruct;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * Reports the SMTP settings in use at startup.
 *
 * Mail failures are swallowed by EmailService so a job still completes, which means a
 * misconfiguration would otherwise only surface at the end of a pipeline that already
 * spent a transcription and a summary. Switching providers is just a matter of changing
 * these values, so it helps to see which ones the running instance actually picked up.
 */
@Component
@Slf4j
public class MailConfigLogger {

    @Value("${spring.mail.host:}")
    private String host;

    @Value("${spring.mail.port:}")
    private String port;

    @Value("${spring.mail.username:}")
    private String username;

    @Value("${spring.mail.password:}")
    private String password;

    @Value("${app.mail.from:${spring.mail.username:}}")
    private String from;

    @PostConstruct
    public void reportMailConfig() {
        if (isBlank(host) || isBlank(username) || isBlank(password)) {
            log.error("SMTP is incompletely configured (host={}, port={}, username={}); emails will fail",
                    orEmpty(host), orEmpty(port), orEmpty(username));
            return;
        }

        // The password is deliberately absent from this line.
        log.info("SMTP ready: host={}:{} username={} from={}", host, orEmpty(port), username, orEmpty(from));

        if (host.contains("gmail.com") && !from.equalsIgnoreCase(username)) {
            log.warn("From address {} differs from the Gmail account {}; Gmail only sends as the account "
                    + "itself or a verified alias and will rewrite or reject it", from, username);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }
}
