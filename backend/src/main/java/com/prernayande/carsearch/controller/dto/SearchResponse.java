package com.prernayande.carsearch.controller.dto;

import com.prernayande.carsearch.domain.ParsedQuery;
import com.prernayande.carsearch.service.LatestService.LatestResult;
import com.prernayande.carsearch.service.SearchService.SearchResult;

import java.util.ArrayList;
import java.util.List;

// Body of GET /api/search and GET /api/latest (query "" for latest)
public record SearchResponse(String query, List<FilterDto> filters, int totalGroups, int page, int size,
                             boolean ranked, String message, long tookMs, List<ResultDto> results) {

    public static SearchResponse from(SearchResult r) {
        return new SearchResponse(r.parsed().original(), filters(r.parsed()), r.totalGroups(), r.page(), r.size(),
                r.ranked(), r.message(), r.tookMs(), r.results().stream().map(g -> ResultDto.from(g, r.ranked())).toList());
    }

    public static SearchResponse from(LatestResult r) {
        return new SearchResponse("", List.of(), r.totalGroups(), r.page(), r.size(), false, null, 0,
                r.results().stream().map(ResultDto::from).toList());
    }

    static List<FilterDto> filters(ParsedQuery p) {
        List<FilterDto> out = new ArrayList<>();
        p.hard().forEach(h -> out.add(new FilterDto(h.label(), "filter")));
        p.soft().forEach(s -> out.add(new FilterDto(s.label(),
                s.inferred() ? "guess" : p.gatedFields().contains(s.field()) ? "filter" : "preference")));
        return out;
    }
}
