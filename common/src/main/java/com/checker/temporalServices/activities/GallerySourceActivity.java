package com.checker.temporalServices.activities;

import com.checker.dto.GallerySourceRequest;
import com.checker.dto.GallerySourceResult;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

import java.io.IOException;

@ActivityInterface
public interface GallerySourceActivity {
    @ActivityMethod
    GallerySourceResult collectGallerySource(GallerySourceRequest request) throws IOException;
}
