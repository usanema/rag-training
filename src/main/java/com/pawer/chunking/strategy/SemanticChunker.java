package com.pawer.chunking.strategy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawer.chunking.ChunkingResult;
import com.pawer.chunking.ChunkingStrategy;
import com.pawer.chunking.PdfChunker;
import com.pawer.config.RagProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.reader.pdf.config.PdfDocumentReaderConfig;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * Semantyczny podział przez LLM.
 *
 * Każda strona/sekcja jest wysyłana do LLM z prośbą o rozbicie na
 * atomowe, samowystarczalne twierdzenia (propositions).
 *
 * Zalety:   najlepsza jakość semantyczna chunków
 * Wady:     wolne (1 wywołanie LLM per strona), drogie
 *
 * Przed użyciem warto zmniejszyć PDF do ~20 stron lub użyć tylko
 * dla najważniejszych sekcji.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SemanticChunker implements PdfChunker {

    private final ChatClient.Builder chatClientBuilder;
    private final RagProperties ragProperties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String PROPOSITION_PROMPT = """
            Twoim zadaniem jest podzielenie poniższego tekstu na atomowe, semantyczne fragmenty.

            Zasady:
            1. Każdy fragment powinien być kompletną, zrozumiałą myślą lub faktem
            2. Fragment musi być zrozumiały bez kontekstu pozostałych fragmentów
            3. Nie łącz niezwiązanych faktów w jednym fragmencie
            4. Zachowaj ważne szczegóły (liczby, nazwy, daty)
            5. Usuń powtórzenia i redundantne informacje

            Zwróć WYŁĄCZNIE poprawny JSON bez żadnego opisu ani znaczników markdown:
            {"chunks": ["fragment1", "fragment2", "fragment3"]}

            Tekst do podziału:
            {text}
            """;

    @Override
    public ChunkingStrategy strategy() {
        return ChunkingStrategy.SEMANTIC;
    }

    @Override
    public ChunkingResult chunk(Resource pdfResource) {
        var cfg = ragProperties.chunking();

        // Wczytaj strony — grupuj po 2 dla redukcji wywołań LLM
        List<Document> pages = new PagePdfDocumentReader(
                pdfResource,
                PdfDocumentReaderConfig.builder()
                        .withPagesPerDocument(2)
                        .build()
        ).read();

        var preSplitter = TokenTextSplitter.builder()
                .withChunkSize(cfg.chunkSize() * 4)
                .withMinChunkSizeChars(cfg.minChunkSize())
                .withMinChunkLengthToEmbed(5)
                .withMaxNumChunks(1000)
                .withKeepSeparator(false)
                .build();


        List<Document> sections = preSplitter.apply(pages);

        log.info("[SEMANTIC] Przetwarzam {} sekcji przez LLM...", sections.size());

        List<Document> chunks = new ArrayList<>();
        var chatClient = chatClientBuilder.build();

        for (int i = 0; i < sections.size(); i++) {
            Document section = sections.get(i);
            String pageNum = (String) section.getMetadata().getOrDefault("page_number", "?");

            log.debug("[SEMANTIC] Sekcja {}/{} (strona {})", i + 1, sections.size(), pageNum);

            try {
                List<String> propositions = callLlm(chatClient, section.getText());

                for (String proposition : propositions) {
                    if (proposition.trim().length() < 20) continue; // pomiń za krótkie

                    var meta = new HashMap<String, Object>();
                    meta.put("page_number", pageNum);
                    meta.put("chunk_type", "semantic_proposition");
                    chunks.add(new Document(proposition.trim(), meta));
                }

                log.debug("[SEMANTIC] Sekcja {} → {} propositions", i + 1, propositions.size());

            } catch (Exception e) {
                log.warn("[SEMANTIC] Błąd LLM dla sekcji {}, fallback na oryginalny tekst: {}", i + 1, e.getMessage());
                chunks.add(section);
            }
        }

        enrichMetadata(chunks, pdfResource.getFilename());

        log.info("[SEMANTIC] {} sekcji → {} chunków (semantic propositions)", sections.size(), chunks.size());
        return ChunkingResult.of(chunks);
    }

    private List<String> callLlm(ChatClient chatClient, String text) throws Exception {
        String response = chatClient.prompt()
                .user(u -> u.text(PROPOSITION_PROMPT).param("text", text))
                .call()
                .content();

        // Wyczyść potencjalne markdown backticks
        String cleaned = response
                .replaceAll("```json", "")
                .replaceAll("```", "")
                .trim();

        JsonNode root = objectMapper.readTree(cleaned);
        JsonNode chunksNode = root.get("chunks");

        List<String> result = new ArrayList<>();
        if (chunksNode != null && chunksNode.isArray()) {
            chunksNode.forEach(node -> result.add(node.asText()));
        }
        return result;
    }
}