package com.prernayande.carsearch.service;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.allminilml6v2q.AllMiniLmL6V2QuantizedEmbeddingModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.CompletableFuture;

// all-MiniLM-L6-v2 (quantized, 384 dims) running inside the JVM via ONNX.
// Loads in the background so the server can answer /api/health while it loads.
@Service
public class OnnxEmbeddingService implements EmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(OnnxEmbeddingService.class);

    private final CompletableFuture<EmbeddingModel> model = new CompletableFuture<>();

    public OnnxEmbeddingService() {
        // own thread with the app's class loader: inside the Spring Boot jar, common-pool threads
        // cannot see the model file and loading fails
        Thread loader = new Thread(() -> {
            try {
                long start = System.currentTimeMillis();
                model.complete(new AllMiniLmL6V2QuantizedEmbeddingModel());
                log.info("Embedding model loaded in {} ms", System.currentTimeMillis() - start);
            } catch (Throwable e) {
                log.error("Embedding model failed to load", e);
                model.completeExceptionally(e);
            }
        });
        loader.setContextClassLoader(OnnxEmbeddingService.class.getClassLoader());
        loader.setDaemon(true);
        loader.start();
    }

    @Override
    public boolean isReady() {
        return model.isDone() && !model.isCompletedExceptionally();
    }

    @Override
    public boolean hasFailed() {
        return model.isCompletedExceptionally();
    }

    @Override
    public float[] embed(String text) {
        return model.join().embed(text).content().vector();
    }

    @Override
    public List<float[]> embedAll(List<String> texts) {
        List<TextSegment> segments = texts.stream().map(TextSegment::from).toList();
        return model.join().embedAll(segments).content().stream().map(e -> e.vector()).toList();
    }
}
