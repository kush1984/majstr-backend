package com.majstr.backend.dto;

import com.majstr.backend.entity.Trade;

/**
 * «Подивитися приклад» (V197). {@code trade}: the one the master just picked on «Чим займаєтесь?»;
 * absent = the first of his own trades that has a sample, else the bathroom.
 */
public record SampleProjectRequest(Trade trade) {
}
