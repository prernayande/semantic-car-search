package com.prernayande.carsearch.service;

import com.prernayande.carsearch.domain.ParsedQuery;

// Turns the typed text into filters, preferences and leftover words. Implementation: RuleBasedQueryParser.
public interface QueryParser {

    ParsedQuery parse(String query);
}
