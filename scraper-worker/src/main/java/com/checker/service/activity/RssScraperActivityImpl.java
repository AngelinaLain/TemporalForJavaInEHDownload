package com.checker.service.activity;

import com.checker.dto.RssSearchPage;
import com.checker.dto.SearchOptions;
import com.checker.manager.search.RssSearchManager;
import com.checker.shared.Constants;
import com.checker.temporal.activity.RssScraperActivity;
import io.temporal.spring.boot.ActivityImpl;
import org.springframework.stereotype.Component;

@Component
@ActivityImpl(taskQueues = Constants.SCRAPER_TASK_QUEUE)
public class RssScraperActivityImpl implements RssScraperActivity {
    private final RssSearchManager search;

    public RssScraperActivityImpl(RssSearchManager search) {
        this.search = search;
    }

    @Override
    public RssSearchPage scrapeRssSearchPage(SearchOptions options, String currentUrl) {
        return search.scrapeRssSearchPage(options, currentUrl);
    }
}
