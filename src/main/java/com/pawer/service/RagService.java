package com.pawer.service;


import com.pawer.config.RagProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class RagService {

    private final VectorStore vectorStore;
    private final ChatClient.Builder chatClientBuilder;
    private final RagProperties ragProperties;

    public String query(String question) {
        var search = ragProperties.search();
        QuestionAnswerAdvisor questionAnswerAdvisor = QuestionAnswerAdvisor.builder(vectorStore)
                .searchRequest(SearchRequest.builder()
                        .topK(search.topK())
                        .similarityThreshold(search.similarityThreshold())
                        .build()).build();
        return chatClientBuilder.build()
                .prompt()
                .advisors(questionAnswerAdvisor)
                .user(question)
                .call()
                .content();
    }
}
