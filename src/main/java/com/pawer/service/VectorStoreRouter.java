package com.pawer.service;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.util.List;

// @Primary — gdy Spring widzi wiele beanów VectorStore, wstrzykuje ten (jak @Qualifier("default"))
// Dzięki temu PdfIngestionService i RagService dostają router bez żadnych zmian.
@Primary
@Service
@Slf4j
public class VectorStoreRouter implements VectorStore {

    public static final List<String> AVAILABLE = List.of("redis", "qdrant");

    private final VectorStore redisStore;
    private final VectorStore qdrantStore;

    // volatile — bezpieczne czytanie/zapis z różnych wątków (jak synchronized getter/setter)
    @Getter
    private volatile String activeStore;

    // @Lazy na parametrach konstruktora = Spring wstrzykuje leniwe proxy zamiast prawdziwego beana.
    // Prawdziwy bean (RedisVectorStore / QdrantVectorStore) tworzy się przy PIERWSZYM wywołaniu metody.
    public VectorStoreRouter(
            @Qualifier("redisVectorStore") @Lazy VectorStore redisStore,
            @Qualifier("qdrantVectorStore") @Lazy VectorStore qdrantStore,
            @Value("${VECTOR_STORE:redis}") String defaultStore) {
        this.redisStore  = redisStore;
        this.qdrantStore = qdrantStore;
        this.activeStore = defaultStore;
        log.info("VectorStoreRouter gotowy — domyślny store: {}", defaultStore);
    }

    // ── API dla kontrolera ────────────────────────────────────────────────────

    public void switchTo(String name) {
        if (!AVAILABLE.contains(name)) {
            throw new IllegalArgumentException("Nieznany store: " + name + ". Dostępne: " + AVAILABLE);
        }
        log.info("Przełączam vector store: {} → {}", activeStore, name);
        activeStore = name;
    }

    // ── VectorStore interface — delegacja do aktywnego store'a ────────────────

    @Override
    public void add(List<Document> documents) {
        current().add(documents);
    }

    @Override
    public void delete(List<String> idList) {
        current().delete(idList);
    }

    @Override
    public void delete(Filter.Expression filterExpression) {
        current().delete(filterExpression);
    }

    @Override
    public List<Document> similaritySearch(SearchRequest request) {
        return current().similaritySearch(request);
    }

    private VectorStore current() {
        return "qdrant".equals(activeStore) ? qdrantStore : redisStore;
    }
}
