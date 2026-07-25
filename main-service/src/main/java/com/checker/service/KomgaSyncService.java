package com.checker.service;

public interface KomgaSyncService {
    void syncTagsToKomga();

    void batchRefreshAllKomgaMetadata();
}
