package com.czx.qqmessageassistantjava.schedule;

import com.czx.qqmessageassistantjava.service.AppSettingService;
import com.czx.qqmessageassistantjava.service.MemoryJobService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MemorySchedulerTest {

    @Mock
    MemoryJobService memoryJobService;

    @Mock
    AppSettingService appSettingService;

    private MemoryScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new MemoryScheduler(memoryJobService, appSettingService);
    }

    @Test
    void runOnce_isNoOpWhenDisabled() {
        when(appSettingService.getOrDefault("memory_job_enabled", "0")).thenReturn("0");

        assertThat(scheduler.runOnce()).isZero();
        verifyNoInteractions(memoryJobService);
    }

    @Test
    void runOnce_delegatesWhenEnabled() {
        when(appSettingService.getOrDefault("memory_job_enabled", "0")).thenReturn("1");
        when(memoryJobService.runOnce()).thenReturn(3);

        assertThat(scheduler.runOnce()).isEqualTo(3);
        verify(memoryJobService).runOnce();
    }
}
