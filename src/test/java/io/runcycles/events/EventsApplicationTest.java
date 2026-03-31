package io.runcycles.events;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EventsApplicationTest {

    @Test
    void mainClass_exists() {
        // Verify the main class exists and has the expected annotations
        assertThat(EventsApplication.class).isNotNull();
        assertThat(EventsApplication.class.getAnnotation(
                org.springframework.boot.autoconfigure.SpringBootApplication.class)).isNotNull();
        assertThat(EventsApplication.class.getAnnotation(
                org.springframework.scheduling.annotation.EnableScheduling.class)).isNotNull();
    }
}
