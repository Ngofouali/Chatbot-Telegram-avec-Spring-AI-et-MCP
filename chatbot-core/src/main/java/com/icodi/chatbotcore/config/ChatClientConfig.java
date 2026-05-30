package com.icodi.chatbotcore.config;

import io.modelcontextprotocol.client.McpSyncClient;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class ChatClientConfig {

    @Bean
    public ChatClient chatClient(ChatClient.Builder builder, List<McpSyncClient> mcpClients) {
        return builder
                .defaultSystem("""
                        Tu es un assistant utile pour une PME marocaine.
                        Répond en français, de façon concise.
                        Utilise les outils disponibles quand cela est pertinent 
                        (catalogue produits, calcul TTC, recherche documentaire, etc.).
                        """)

                .
                .build();
    }

}
