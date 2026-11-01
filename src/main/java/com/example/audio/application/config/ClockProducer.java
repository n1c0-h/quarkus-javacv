package com.example.audio.application.config;

import java.time.Clock;
import java.time.Duration;

import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

public class ClockProducer {

    /**
     * Microsecond ticks: the precision PostgreSQL stores, so read values equal written ones. Singleton: a clock is
     * immutable and needs no client proxy.
     */
    @Produces
    @Singleton
    Clock clock() {
        return Clock.tick(Clock.systemUTC(), Duration.ofNanos(1_000));
    }
}
