package com.flightbooking.service;

import com.flightbooking.domain.entity.User;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Stub notifications channel. Replace with SES / SendGrid / SNS / Kafka topic
 * as needed — the callers only see this interface.
 */
@Slf4j
@Service
public class NotificationService {

    public void notifyUser(User user, String subject, String body) {
        log.info("[notify] to={} <{}> subject='{}' body='{}'",
                user.getName(), user.getEmail(), subject, body);
    }
}
