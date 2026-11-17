package com.checker.rss;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RssSubscriptionRequestTest {
    @Test void legacySubscriptionsKeepCollectionsDisabled() throws Exception {
        var request=new ObjectMapper().readValue("{\"name\":\"已有订阅\",\"keyword\":\"test\"}",RssSubscriptionRequest.class);
        request.validate();
        assertFalse(request.isCollectionEnabled());assertNull(request.getCollectionId());
    }
    @Test void collectionIsRequiredOnlyWhenEnabled() {
        var request=new RssSubscriptionRequest();request.setName("测试");request.setKeyword("test");
        request.setCollectionId(-1L);request.validate();assertNull(request.getCollectionId());
        request.setCollectionEnabled(true);
        assertThrows(IllegalArgumentException.class,request::validate);
        request.setCollectionId(0L);assertThrows(IllegalArgumentException.class,request::validate);
        request.setCollectionId(123L);assertDoesNotThrow(request::validate);
        request.setCollectionEnabled(false);request.validate();assertNull(request.getCollectionId());
    }
}
