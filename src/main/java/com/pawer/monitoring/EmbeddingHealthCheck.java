package com.pawer.monitoring;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class EmbeddingHealthCheck implements ApplicationRunner {

    private final EmbeddingModel embeddingModel;

    @Override
    public void run(ApplicationArguments args) {
        try {
            long t0 = System.currentTimeMillis();
            float[] vector = embeddingModel.embed("test");
            log.info("✅ Lokalny model embeddingowy działa | wymiar wektora: {}. Czas odpowiedzi : {}", vector.length, (System.currentTimeMillis() - t0) / 1_000);
        } catch (Exception e) {
            log.error("❌ Brak połączenia z modelem embeddingowym: {}", e.getMessage());
        }
    }
}
