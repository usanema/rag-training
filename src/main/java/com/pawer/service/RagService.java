package com.pawer.service;


import com.pawer.config.RagProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

@Service
@Slf4j
@RequiredArgsConstructor
public class RagService {

    private final VectorStore vectorStore;
    private final ChatClient.Builder chatClientBuilder;
    private final RagProperties ragProperties;

    public String query(String question) {
        long t0 = System.currentTimeMillis();
        var search = ragProperties.search();
        QuestionAnswerAdvisor questionAnswerAdvisor = QuestionAnswerAdvisor.builder(vectorStore)
                .searchRequest(SearchRequest.builder()
                        .topK(search.topK())
                        .similarityThreshold(search.similarityThreshold())
                        .build()).build();
        log.info("Search time: {} ms", System.currentTimeMillis() - t0);
        String responseContent = chatClientBuilder.build()
                .prompt()
                .advisors(questionAnswerAdvisor)
                .user(question)
                .call()
                .content();
        log.info("Model Response time: {} ms", System.currentTimeMillis() - t0);
        return responseContent;
    }

    public Flux<String> query_stream(String question) {
        long t0 = System.currentTimeMillis();
        var search = ragProperties.search();
        log.info("Search time: {} ms", System.currentTimeMillis() - t0);
        QuestionAnswerAdvisor questionAnswerAdvisor = QuestionAnswerAdvisor.builder(vectorStore)
                .searchRequest(SearchRequest.builder()
                        .topK(search.topK())
                        .similarityThreshold(search.similarityThreshold())
                        .build()).build();

        return chatClientBuilder.build()
                .prompt()
                .advisors(questionAnswerAdvisor)
                .user(question)
                .stream()
                .content();
    }
}
