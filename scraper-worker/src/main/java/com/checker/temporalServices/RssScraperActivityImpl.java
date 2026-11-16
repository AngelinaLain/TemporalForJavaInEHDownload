package com.checker.temporalServices;

import com.checker.common.Constants;
import com.checker.common.EhNetworkClient;
import com.checker.common.EhSearchPageParser;
import com.checker.common.EhSearchQueryBuilder;
import com.checker.dto.RssSearchPage;
import com.checker.dto.SearchOptions;
import com.checker.temporalServices.activities.RssScraperActivity;
import io.temporal.spring.boot.ActivityImpl;
import org.springframework.stereotype.Component;

@Component
@ActivityImpl(taskQueues = Constants.SCRAPER_TASK_QUEUE)
public class RssScraperActivityImpl implements RssScraperActivity {
    private final EhNetworkClient client;
    public RssScraperActivityImpl(EhNetworkClient client) { this.client = client; }
    @Override public RssSearchPage scrapeRssSearchPage(SearchOptions options, String currentUrl) {
        String url = currentUrl == null ? EhSearchQueryBuilder.searchUrl(options) : EhSearchPageParser.validateCursor(currentUrl);
        return EhSearchPageParser.parse(client.getHtml(url), url);
    }
}
