package com.checker.service;

import com.checker.common.DedupeSettings;
import com.checker.mapper.DedupeSettingsMapper;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DedupeSettingsServiceTest {
    @Test void missingSettingsUseDefaultsWithoutCreatingARow() {
        DedupeSettingsMapper mapper = mock(DedupeSettingsMapper.class);
        assertEquals(DedupeSettings.defaults(), new DedupeSettingsService(mapper).current());
        verify(mapper).current();
        verifyNoMoreInteractions(mapper);
    }

    @Test void eachOperationSeesLatestPersistedSettingsAndDatabaseFailureDoesNotChangeThePolicy() {
        DedupeSettingsMapper mapper = mock(DedupeSettingsMapper.class);
        DedupeSettings changed = new DedupeSettings(70, 92, 90, 12, 5, 85, 8);
        when(mapper.current()).thenReturn(DedupeSettings.defaults(), changed)
                .thenThrow(new DataAccessResourceFailureException("database unavailable"));
        DedupeSettingsService service = new DedupeSettingsService(mapper);
        assertEquals(DedupeSettings.defaults(), service.current());
        assertEquals(changed, service.current());
        assertThrows(DataAccessResourceFailureException.class, service::current);
        verify(mapper, never()).save(any());
    }

    @Test void savingIsOneAtomicWriteAndNullNeverReplacesTheCurrentPolicy() {
        DedupeSettingsMapper mapper = mock(DedupeSettingsMapper.class);
        DedupeSettingsService service = new DedupeSettingsService(mapper);
        DedupeSettings changed = new DedupeSettings(70, 92, 90, 12, 5, 85, 8);
        assertThrows(IllegalArgumentException.class, () -> service.save(null));
        assertSame(changed, service.save(changed));
        verify(mapper).save(changed);
        verifyNoMoreInteractions(mapper);
    }
}
