package com.pawer.config;

import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.qdrant.QdrantVectorStore;
import org.springframework.ai.vectorstore.redis.RedisVectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import redis.clients.jedis.RedisClient;

@Slf4j
@Configuration
@EnableConfigurationProperties(RagProperties.class)
public class AppConfig {

    // ── Redis ────────────────────────────────────────────────────────────────

    @Value("${REDIS_HOST:localhost}")
    private String redisHost;

    @Value("${REDIS_PORT:6379}")
    private int redisPort;

    @Value("${REDIS_INDEX:documents}")
    private String redisIndex;

    @Value("${REDIS_PREFIX:doc:}")
    private String redisPrefix;

    // ── Qdrant ───────────────────────────────────────────────────────────────

    @Value("${QDRANT_HOST:localhost}")
    private String qdrantHost;

    @Value("${QDRANT_PORT:6334}")
    private int qdrantPort;

    @Value("${QDRANT_COLLECTION:documents}")
    private String qdrantCollection;

    @Value("${QDRANT_TLS:false}")
    private boolean qdrantTls;

    // ── Beans ────────────────────────────────────────────────────────────────

    // MessageWindowChatMemory przechowuje historię rozmów per conversationId.
    // InMemoryChatMemoryRepository to magazyn — jak Map<conversationId, List<Message>>.
    // maxMessages=20 = max 20 ostatnich wiadomości w kontekście (nie wysyłamy całej historii do LLM).
    @Bean
    public ChatMemory chatMemory() {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(new InMemoryChatMemoryRepository())
                .maxMessages(20)
                .build();
    }

    // EmbeddingModel pochodzi teraz z autoconfiguration (spring.ai.openai.embedding.*).
    // Oba vector store przyjmują go przez wstrzyknięcie.

    // @Lazy = bean tworzony dopiero przy pierwszym użyciu (analogia: lazy loading w JPA).
    // Dzięki temu aplikacja startuje bez podłączania do baz danych — połączenie
    // nawiązywane jest dopiero gdy użytkownik wykona pierwszą operację na danym store.

    @Lazy
    @Bean("redisVectorStore")
    public VectorStore redisVectorStore(EmbeddingModel embeddingModel) {
        log.info("Inicjalizacja RedisVectorStore → {}:{}", redisHost, redisPort);
        RedisClient jedis = RedisClient.create(redisHost, redisPort);
        return RedisVectorStore.builder(jedis, embeddingModel)
                .indexName(redisIndex)
                .prefix(redisPrefix)
                .initializeSchema(true)
                .metadataFields(
                        RedisVectorStore.MetadataField.text("source"),
                        RedisVectorStore.MetadataField.text("page_number"),
                        RedisVectorStore.MetadataField.text("chunk_type"),
                        RedisVectorStore.MetadataField.text("parentId"),
                        RedisVectorStore.MetadataField.text("section_title")
                )
                .build();
    }

    @Lazy
    @Bean("qdrantVectorStore")
    public VectorStore qdrantVectorStore(EmbeddingModel embeddingModel) {
        log.info("Inicjalizacja QdrantVectorStore → {}:{}", qdrantHost, qdrantPort);
        QdrantClient client = new QdrantClient(
                QdrantGrpcClient.newBuilder(qdrantHost, qdrantPort, qdrantTls).build());
        return QdrantVectorStore.builder(client, embeddingModel)
                .collectionName(qdrantCollection)
                .initializeSchema(true)
                .build();
    }
}
