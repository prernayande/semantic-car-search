package com.prernayande.carsearch.controller.dto;

// model: LOADING | READY | FAILED
public record HealthResponse(String status, String model) {}
