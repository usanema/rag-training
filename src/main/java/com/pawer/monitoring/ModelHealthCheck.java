package com.pawer.monitoring;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@AllArgsConstructor
public class ModelHealthCheck implements ApplicationRunner {

    private final ChatClient.Builder chatClientBuilder;

    @Override
    public void run(ApplicationArguments args) throws Exception {
        try {
            long t0 = System.currentTimeMillis();
            String responseContent = chatClientBuilder.build()
                    .prompt()
                    .system("Jestes healthchekiem modelu. Odpowiadasz jedynie tak lub nie.")
                    .user("Czy model działa ?")
                    .call()
                    .content();
            log.info("✅ Lokalny model działa | Odpowiedź od modelu: {}. Czas opowiedzi : {} s", responseContent, (System.currentTimeMillis() - t0)/1_000);
        } catch (Exception e) {
            log.error("❌ Brak połączenia z modelem : {}", e.getMessage());
        }
    }
}
