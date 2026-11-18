package com.checker.manager.search;

import com.checker.infrastructure.network.EhNetworkClient;
import com.checker.search.EhSearchQueryBuilder;
import com.checker.dto.RssSearchPage;
import com.checker.dto.SearchOptions;
import org.springframework.stereotype.Component;

@Component
public class RssSearchManager {
    private final EhNetworkClient client;
    public RssSearchManager(EhNetworkClient client) { this.client = client; }
    public RssSearchPage scrapeRssSearchPage(SearchOptions options, String currentUrl) {
        String url = currentUrl == null ? EhSearchQueryBuilder.searchUrl(options) : EhSearchPageParser.validateCursor(currentUrl);
        return EhSearchPageParser.parse(client.getHtml(url), url);
    }
}
