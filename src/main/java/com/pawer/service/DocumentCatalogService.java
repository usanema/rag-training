package com.pawer.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;
import redis.clients.jedis.JedisPooled;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@Slf4j
public class DocumentCatalogService {

    private static final String CATALOG_KEY = "doc:catalog";
    private static final int SUMMARY_CHUNKS = 3;

    private static final String SUMMARY_PROMPT = """
            Przeczytaj poniższy fragment dokumentu i napisz 2-3 zdania opisujące:
            1. O czym jest ten dokument?
            2. Jakie główne tematy lub informacje zawiera?

            Odpowiedź powinna pomóc zdecydować, czy ten dokument zawiera odpowiedź na pytanie użytkownika.
            Pisz zwięźle i konkretnie. Pisz w języku dokumentu.

            Fragment dokumentu:
            {text}
            """;

    private final JedisPooled jedis;
    private final ChatClient chatClient;

    public DocumentCatalogService(JedisPooled jedis, ChatClient.Builder chatClientBuilder) {
        this.jedis = jedis;
        this.chatClient = chatClientBuilder.build();
    }

    public void generateAndStore(String filename, List<Document> chunks) {
        if (chunks.isEmpty()) return;

        String context = chunks.stream()
                .limit(SUMMARY_CHUNKS)
                .map(Document::getText)
                .collect(Collectors.joining("\n\n"));

        try {
            String summary = chatClient.prompt()
                    .user(u -> u.text(SUMMARY_PROMPT).param("text", context))
                    .call()
                    .content();
            jedis.hset(CATALOG_KEY, filename, summary.strip());
            log.info("Katalog: dodano summary dla '{}'", filename);
        } catch (Exception e) {
            // Fallback — brak summary nie blokuje ingesztu
            jedis.hset(CATALOG_KEY, filename, filename);
            log.warn("Nie udało się wygenerować summary dla '{}', zapisano nazwę pliku: {}", filename, e.getMessage());
        }
    }

    public Map<String, String> loadCatalog() {
        return jedis.hgetAll(CATALOG_KEY);
    }

    public void remove(String filename) {
        jedis.hdel(CATALOG_KEY, filename);
        log.info("Katalog: usunięto '{}'", filename);
    }
}
