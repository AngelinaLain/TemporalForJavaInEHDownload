package com.checker.dto;

import java.util.List;
public record RssSearchPage(List<RssSearchItem> items, String nextUrl, boolean endOfResults) {}
