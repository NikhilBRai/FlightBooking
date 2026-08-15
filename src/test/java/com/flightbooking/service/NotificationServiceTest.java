package com.flightbooking.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.flightbooking.domain.entity.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The service is a stub — its only observable behaviour is emitting a
 * structured INFO log line per notification. We plug a Logback list
 * appender into the service's logger and assert on captured events, so
 * the test breaks the day we accidentally silence the notification
 * channel (or change the log format that a downstream log-scraper
 * might depend on).
 */
class NotificationServiceTest {

    private NotificationService service;
    private ListAppender<ILoggingEvent> appender;
    private Logger logger;

    @BeforeEach
    void setUp() {
        service = new NotificationService();
        logger = (Logger) LoggerFactory.getLogger(NotificationService.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.INFO);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
    }

    @Test
    void emitsInfoLogWithSubjectAndBodyAndRecipient() {
        User user = User.builder().id(7L).name("Alice").email("alice@example.com").build();

        service.notifyUser(user, "Booking confirmed", "Your booking 42 is confirmed.");

        assertThat(appender.list).hasSize(1);
        ILoggingEvent event = appender.list.get(0);
        assertThat(event.getLevel()).isEqualTo(Level.INFO);
        String rendered = event.getFormattedMessage();
        assertThat(rendered)
                .contains("Alice")
                .contains("alice@example.com")
                .contains("Booking confirmed")
                .contains("Your booking 42 is confirmed.");
    }
}
