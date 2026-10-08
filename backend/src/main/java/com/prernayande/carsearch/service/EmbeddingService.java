package com.prernayande.carsearch.service;

import java.util.List;

// Text -> 384-dim vector. Implementation: OnnxEmbeddingService (in-process model); a hosted API could replace it.
public interface EmbeddingService {

    boolean isReady();

    boolean hasFailed();

    float[] embed(String text);

    List<float[]> embedAll(List<String> texts);
}
