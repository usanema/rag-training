package com.pawer.routing;

import com.google.adk.agents.LlmAgent;
import com.google.adk.artifacts.InMemoryArtifactService;
import com.google.adk.models.springai.SpringAI;
import com.google.adk.runner.Runner;
import com.google.adk.sessions.InMemorySessionService;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;
import java.util.stream.Collectors;

@Configuration
public class AdkAgentConfig {

    public static final String APP_NAME = "rag-studio";

    // Szablon bazowy — bez sekcji dokumentów (dodawana dynamicznie w RagService)
    public static final String BASE_INSTRUCTION = """
            You are a helpful assistant with access to a document knowledge base via the rag_search tool.

            Use rag_search when the question involves specific documents, contracts, reports,
            data, or content that might exist in indexed files. You may call rag_search multiple
            times with different queries to gather complete information before answering.

            Answer directly without the tool for general knowledge, math, programming concepts,
            or anything clearly not tied to specific documents.

            Always respond in the same language as the user's question.
            Format responses in Markdown: headers (##), lists (-), bold (**text**), code blocks (```).
            """;

    // Sesje przechowywane w pamięci współdzielonej — przeżywają między requestami.
    @Bean
    public InMemorySessionService adkSessionService() {
        return new InMemorySessionService();
    }

    @Bean
    public InMemoryArtifactService adkArtifactService() {
        return new InMemoryArtifactService();
    }

    // Buduje instrukcję agenta z katalogiem dokumentów.
    // Wywoływany per-query przez RagService z aktualnym stanem katalogu.
    public static String buildInstruction(Map<String, String> catalog) {
        if (catalog == null || catalog.isEmpty()) return BASE_INSTRUCTION;

        String docList = catalog.entrySet().stream()
                .map(e -> "- " + e.getKey() + ": " + e.getValue())
                .collect(Collectors.joining("\n"));

        return BASE_INSTRUCTION + """

                Documents available in the knowledge base:
                """ + docList + """

                When the question concerns the content of these documents — call rag_search.
                """;
    }

    // Tworzy Runner per-query z dynamiczną instrukcją i współdzieloną session service.
    public static Runner buildRunner(
            ChatModel chatModel,
            RagTool ragTool,
            InMemorySessionService sessionService,
            InMemoryArtifactService artifactService,
            Map<String, String> catalog) {

        LlmAgent agent = LlmAgent.builder()
                .name("rag-agent")
                .description("Assistant that answers questions with optional document retrieval")
                .model(new SpringAI(chatModel))
                .instruction(buildInstruction(catalog))
                .tools(ragTool.toFunctionTool())
                .build();

        return new Runner(agent, APP_NAME, artifactService, sessionService);
    }
}
