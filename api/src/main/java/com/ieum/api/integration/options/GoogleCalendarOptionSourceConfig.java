package com.ieum.api.integration.options;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 캘린더 공급원 2키 — 구현은 {@link GoogleCalendarsOptionSource} 하나, 쓰기 필터·꼬리표만 다르다. */
@Configuration
public class GoogleCalendarOptionSourceConfig {

    @Bean
    public OptionSource googleCalendarsOptionSource(GoogleApiReader googleApiReader) {
        return new GoogleCalendarsOptionSource("google.calendars", false, googleApiReader);
    }

    @Bean
    public OptionSource googleWritableCalendarsOptionSource(GoogleApiReader googleApiReader) {
        return new GoogleCalendarsOptionSource("google.writable_calendars", true, googleApiReader);
    }
}
