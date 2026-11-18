package com.checker.temporal.activity;

import com.checker.dto.RssSearchPage;
import com.checker.dto.SearchOptions;
import io.temporal.activity.ActivityInterface;

@ActivityInterface
public interface RssScraperActivity {
    RssSearchPage scrapeRssSearchPage(SearchOptions options, String currentUrl);
}
