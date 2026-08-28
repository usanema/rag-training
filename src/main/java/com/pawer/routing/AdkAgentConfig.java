package com.pawer.routing;

import com.google.adk.agents.LlmAgent;
import com.google.adk.models.springai.SpringAI;
import com.google.adk.runner.InMemoryRunner;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AdkAgentConfig {

    private static final String AGENT_INSTRUCTION = """
            You are a helpful assistant with access to a document knowledge base.

            When the user's question is about specific documents, reports, contracts, data,
            or any content that might exist in indexed files — call the rag_search tool.

            When the question is about general knowledge, math, programming concepts,
            or anything not tied to specific documents — answer directly without using the tool.

            Always answer in the same language as the user's question.
            Format responses in Markdown: headers (##), lists (-), bold (**text**), code blocks (```).
            """;

    @Bean
    public LlmAgent ragLlmAgent(ChatModel chatModel, RagTool ragTool) {
        return LlmAgent.builder()
                .name("rag-agent")
                .description("Assistant that answers questions with optional document retrieval")
                .model(new SpringAI(chatModel))
                .instruction(AGENT_INSTRUCTION)
                .tools(ragTool.toFunctionTool())
                .build();
    }

    @Bean
    public InMemoryRunner adkRunner(LlmAgent ragLlmAgent) {
        return new InMemoryRunner(ragLlmAgent, "rag-studio");
    }
}
