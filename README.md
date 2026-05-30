# 🤖 Chatbot Spring AI MCP — Telegram, RAG & Microservices

> README pédagogique inspiré de la série de vidéos **"AI Agent Chatbot With Spring AI MCP and Telegram Client"** de **Mohamed Youssfi** ([vidéo source](https://www.youtube.com/watch?v=Q12plqwksxk) — repo de référence : [`mohamedYoussfi/chatbot-spring-ai-mcp-telegram-client`](https://github.com/mohamedYoussfi/chatbot-spring-ai-mcp-telegram-client)).

Ce document décrit pas à pas la construction d'un agent conversationnel basé sur **Spring AI**, exposant ses capacités via le **protocole MCP (Model Context Protocol)**, consommé depuis un **bot Telegram**, enrichi par un **système RAG (Retrieval-Augmented Generation)**, puis intégré dans une **architecture micro-services** complète.

---

## 📑 Table des matières

- [Vue d'ensemble](#-vue-densemble)
- [Prérequis](#-prérequis)
- [PARTIE 1 — Chatbot Spring AI + MCP + Telegram](#-partie-1--chatbot-spring-ai--mcp--telegram)
  - [1.1 Architecture cible](#11-architecture-cible)
  - [1.2 Création du Bot Telegram](#12-création-du-bot-telegram-via-botfather)
  - [1.3 Module `mcp-server` (outils métier)](#13-module-mcp-server--outils-métier)
  - [1.4 Module `chatbot-core` (Spring AI + MCP Client)](#14-module-chatbot-core--spring-ai--mcp-client)
  - [1.5 Module `telegram-client`](#15-module-telegram-client)
  - [1.6 Démarrage et tests](#16-démarrage-et-tests)
- [PARTIE 2 — Ajout du système RAG](#-partie-2--ajout-du-système-rag)
  - [2.1 Principe du RAG](#21-principe-du-rag)
  - [2.2 Nouveau module `rag-service`](#22-nouveau-module-rag-service)
  - [2.3 Ingestion de documents](#23-ingestion-de-documents)
  - [2.4 Exposition du RAG comme outil MCP](#24-exposition-du-rag-comme-outil-mcp)
  - [2.5 Intégration côté `chatbot-core`](#25-intégration-côté-chatbot-core)
- [PARTIE 3 — Architecture micro-services](#-partie-3--architecture-micro-services)
  - [3.1 Vue cible](#31-vue-cible)
  - [3.2 Config Server](#32-config-server)
  - [3.3 Service Discovery (Eureka)](#33-service-discovery-eureka)
  - [3.4 API Gateway](#34-api-gateway)
  - [3.5 Sécurité (Keycloak / OAuth2)](#35-sécurité-keycloak--oauth2)
  - [3.6 Observabilité](#36-observabilité)
  - [3.7 Docker Compose & déploiement](#37-docker-compose--déploiement)
- [Roadmap & ressources](#-roadmap--ressources)

---

## 🎯 Vue d'ensemble

L'application complète est composée des services suivants :

| Service           | Rôle                                                                     | Port |
|-------------------|--------------------------------------------------------------------------|------|
| `mcp-server`      | Expose des outils métier (fonctions) via le protocole MCP                | 8090 |
| `rag-service`     | Service de recherche vectorielle (Partie 2) — également exposé en MCP    | 8091 |
| `chatbot-core`    | Hôte Spring AI : appelle le LLM, consomme les outils MCP                 | 8888 |
| `telegram-client` | Pont entre Telegram et `chatbot-core` (REST)                             | 8081 |
| `config-server`   | Centralise la configuration Spring Cloud Config (Partie 3)               | 8888 |
| `discovery`       | Eureka Server (Partie 3)                                                 | 8761 |
| `gateway`         | Spring Cloud Gateway (Partie 3)                                          | 8080 |

---

## ✅ Prérequis

- **JDK 21+**
- **Maven 3.9+**
- **Docker / Docker Compose**
- Un **compte Telegram** + un **token bot** obtenu via [@BotFather](https://t.me/BotFather)
- Une **clé API LLM** : OpenAI, Ollama (local), Groq, ou Mistral (selon l'`application.yml`)
- (Partie 2) Une base **PostgreSQL avec extension `pgvector`** *ou* **Chroma / Redis Stack**
- (Partie 3) Docker, Keycloak, Prometheus + Grafana (facultatif)

---

# 🧩 PARTIE 1 — Chatbot Spring AI + MCP + Telegram

## 1.1 Architecture cible

```                           
                                                          ┌──────────────┐
                                                          │    Fichier   │
                                                          │      PDF     │
                                                          └──────────────┘    
                                                                   │       
┌──────────────┐   HTTPS    ┌──────────────────┐   REST    ┌──────────────┐   MCP (stdio/sse)   ┌─────────────┐
│  Telegram    │ ─────────► │ telegram-client  │ ────────► │ chatbot-core │ ──────────────────► │ mcp-server  │
│  (BotFather) │ ◄───────── │  (Spring Boot)   │ ◄──────── │ (Spring AI)  │ ◄────────────────── │ (@Tool)     │
└──────────────┘            └──────────────────┘           └──────┬───────┘                     └─────────────┘
                                                                  │ Chat API
                                                                  ▼
                                                          ┌──────────────┐
                                                          │  LLM (OpenAI │
                                                          │  / Ollama …) │
                                                          └──────────────┘
```

- **`mcp-server`** : application Spring Boot qui déclare des `@Tool` (calculs, accès BDD, appels d'API externes, météo, etc.) et les expose en MCP.
- **`chatbot-core`** : hôte Spring AI. Il configure un `ChatClient` qui découvre les outils MCP via `spring-ai-starter-mcp-client`. Le LLM peut alors faire du *function calling* vers les outils du serveur MCP.
- **`telegram-client`** : application Spring Boot qui écoute les updates Telegram (long-polling) et relaie chaque message à `chatbot-core` via REST.

## 1.2 Création du Bot Telegram via BotFather

1. Sur Telegram, ouvrir une conversation avec **@BotFather**.
2. Envoyer `/newbot`, choisir un nom puis un username (`xxx_bot`).
3. Récupérer le **token** affiché (forme `123456789:AAH...`).
4. (Optionnel) `/setdescription`, `/setuserpic`, `/setcommands` pour personnaliser le bot.

> ⚠️ **Sécurité** : ne jamais committer ce token. Placer dans `application-secret.yml` (non versionné) ou variable d'environnement `TELEGRAM_BOT_TOKEN`.

## 1.3 Module `mcp-server` — outils métier

### `pom.xml` (extraits clés)

```xml
<parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>3.4.0</version>
</parent>

<properties>
    <java.version>21</java.version>
    <spring-ai.version>1.0.0</spring-ai.version>
</properties>

<dependencies>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-web</artifactId>
    </dependency>
    <!-- Exposition du serveur MCP via Spring AI -->
    <dependency>
        <groupId>org.springframework.ai</groupId>
        <artifactId>spring-ai-starter-mcp-server-webmvc</artifactId>
    </dependency>
</dependencies>

<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>org.springframework.ai</groupId>
            <artifactId>spring-ai-bom</artifactId>
            <version>${spring-ai.version}</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>
```

### `application.yml`

```yaml
server:
  port: 8090
spring:
  application:
    name: mcp-server
  ai:
    mcp:
      server:
        name: mcp-tools-server
        version: 1.0.0
        type: SYNC
        sse-message-endpoint: /mcp/messages
```

### Définition des outils (`@Tool`)

```java
package ma.enset.mcpserver.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;
import java.time.LocalDate;
import java.util.List;

@Service
public class BusinessTools {

    @Tool(description = "Retourne la date du jour au format ISO.")
    public String currentDate() {
        return LocalDate.now().toString();
    }

    @Tool(description = "Liste les produits disponibles dans le catalogue.")
    public List<Product> listProducts() {
        return List.of(
            new Product(1L, "Ordinateur portable", 12000),
            new Product(2L, "Imprimante laser",   2500),
            new Product(3L, "Smartphone",         4500)
        );
    }

    @Tool(description = "Calcule le prix TTC à partir d'un montant HT et d'un taux de TVA en pourcentage.")
    public double computeTtc(
            @ToolParam(description = "Montant hors taxe") double ht,
            @ToolParam(description = "Taux de TVA en %") double tva) {
        return ht * (1 + tva / 100.0);
    }

    public record Product(Long id, String name, double price) {}
}
```

### Enregistrement des outils

```java
package ma.enset.mcpserver;

import ma.enset.mcpserver.tools.BusinessTools;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class McpServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(McpServerApplication.class, args);
    }

    @Bean
    public ToolCallbackProvider toolCallbackProvider(BusinessTools tools) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(tools)
                .build();
    }
}
```

> Le serveur MCP est désormais accessible en SSE sur `http://localhost:8090/sse`.

## 1.4 Module `chatbot-core` — Spring AI + MCP Client

### Dépendances clés

```xml
<dependencies>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-web</artifactId>
    </dependency>
    <!-- Choisir UN provider LLM -->
    <dependency>
        <groupId>org.springframework.ai</groupId>
        <artifactId>spring-ai-starter-model-openai</artifactId>
    </dependency>
    <!-- Client MCP (SSE) -->
    <dependency>
        <groupId>org.springframework.ai</groupId>
        <artifactId>spring-ai-starter-mcp-client</artifactId>
    </dependency>
</dependencies>
```

### `application.yml`

```yaml
server:
  port: 8888
spring:
  application:
    name: chatbot-core
  ai:
    openai:
      api-key: ${OPENAI_API_KEY}
      chat:
        options:
          model: gpt-4o-mini
          temperature: 0.3
    mcp:
      client:
        sse:
          connections:
            mcp-tools-server:
              url: http://localhost:8090
            # Partie 2 : on ajoutera ici le rag-service
            # rag-service:
            #   url: http://localhost:8091
```

### `ChatClient` avec outils MCP

```java
package ma.enset.chatbotcore.config;

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
                Réponds en français, de façon concise.
                Utilise les outils disponibles quand cela est pertinent
                (catalogue produits, calcul TTC, recherche documentaire, etc.).
            """)
            .defaultToolCallbacks(new SyncMcpToolCallbackProvider(mcpClients))
            .build();
    }
}
```

### Contrôleur REST exposé au `telegram-client`

```java
package ma.enset.chatbotcore.web;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final ChatClient chatClient;

    public ChatController(ChatClient chatClient) { this.chatClient = chatClient; }

    public record ChatRequest(String userId, String message) {}
    public record ChatResponse(String reply) {}

    @PostMapping
    public ChatResponse chat(@RequestBody ChatRequest req) {
        String reply = chatClient.prompt()
                .user(req.message())
                // Mémoire conversationnelle simple (clé = userId)
                .advisors(a -> a.param("chat_memory_conversation_id", req.userId()))
                .call()
                .content();
        return new ChatResponse(reply);
    }
}
```

> 💡 Pour la **mémoire conversationnelle**, ajouter `spring-ai-advisors-chat-memory` + un `InMemoryChatMemory` (ou JDBC/Redis pour persister par `chatId` Telegram).

## 1.5 Module `telegram-client`

### Dépendance Telegram

```xml
<dependency>
    <groupId>org.telegram</groupId>
    <artifactId>telegrambots-longpolling</artifactId>
    <version>7.10.0</version>
</dependency>
<dependency>
    <groupId>org.telegram</groupId>
    <artifactId>telegrambots-client</artifactId>
    <version>7.10.0</version>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-web</artifactId>
</dependency>
```

### `application.yml`

```yaml
server:
  port: 8081
telegram:
  bot:
    username: my_company_bot
    token: ${TELEGRAM_BOT_TOKEN}
chatbot:
  core:
    url: http://localhost:8888
```

### Long-polling et relais REST

```java
package ma.enset.telegramclient.bot;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.telegram.telegrambots.longpolling.util.LongPollingSingleThreadUpdateConsumer;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;

@Component
public class ChatbotBot implements LongPollingSingleThreadUpdateConsumer {

    private final OkHttpTelegramClient telegramClient;
    private final RestClient restClient;

    public ChatbotBot(@Value("${telegram.bot.token}") String token,
                      @Value("${chatbot.core.url}") String coreUrl) {
        this.telegramClient = new OkHttpTelegramClient(token);
        this.restClient = RestClient.builder().baseUrl(coreUrl).build();
    }

    @Override
    public void consume(Update update) {
        if (!update.hasMessage() || !update.getMessage().hasText()) return;
        Long chatId = update.getMessage().getChatId();
        String text = update.getMessage().getText();

        var resp = restClient.post()
            .uri("/api/chat")
            .body(new ChatRequest(chatId.toString(), text))
            .retrieve()
            .body(ChatResponse.class);

        try {
            telegramClient.execute(SendMessage.builder()
                .chatId(chatId)
                .text(resp != null ? resp.reply() : "⚠️ Erreur côté serveur.")
                .build());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public record ChatRequest(String userId, String message) {}
    public record ChatResponse(String reply) {}
}
```

### Enregistrement de l'application Telegram

```java
package ma.enset.telegramclient;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.longpolling.TelegramBotsLongPollingApplication;
import ma.enset.telegramclient.bot.ChatbotBot;

@SpringBootApplication
public class TelegramClientApplication {
    public static void main(String[] args) {
        SpringApplication.run(TelegramClientApplication.class, args);
    }

    @Component
    static class BotStarter {
        private final ChatbotBot bot;
        private final String token;

        BotStarter(ChatbotBot bot, @Value("${telegram.bot.token}") String token) {
            this.bot = bot; this.token = token;
        }

        @PostConstruct
        void register() throws Exception {
            try (var app = new TelegramBotsLongPollingApplication()) {
                app.registerBot(token, bot);
                Thread.currentThread().join(); // bloquer le thread Spring
            }
        }
    }
}
```

## 1.6 Démarrage et tests

```bash
# 1. Lancer le serveur MCP
cd mcp-server && mvn spring-boot:run

# 2. Lancer le cœur du chatbot
cd ../chatbot-core && OPENAI_API_KEY=sk-... mvn spring-boot:run

# 3. Lancer le client Telegram
cd ../telegram-client && TELEGRAM_BOT_TOKEN=123:AAH... mvn spring-boot:run
```

Ouvrir Telegram, taper à votre bot :

- *"Quels produits avez-vous en catalogue ?"* → déclenche `listProducts`
- *"Prix TTC de 1000 DH avec 20% de TVA ?"* → déclenche `computeTtc`
- *"Quelle est la date d'aujourd'hui ?"* → déclenche `currentDate`

---

# 📚 PARTIE 2 — Ajout du système RAG

## 2.1 Principe du RAG

Le **RAG (Retrieval-Augmented Generation)** enrichit les réponses du LLM avec un contexte issu d'une base documentaire propriétaire :

```
1. Ingestion : documents → split en chunks → embeddings → VectorStore
2. Requête   : question utilisateur → embedding → recherche k-NN dans le VectorStore
3. Génération: chunks pertinents + question → prompt → LLM → réponse sourcée
```

## 2.2 Nouveau module `rag-service`

### Dépendances

```xml
<dependencies>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-web</artifactId>
    </dependency>
    <!-- Modèle d'embeddings (OpenAI, Ollama ou Mistral) -->
    <dependency>
        <groupId>org.springframework.ai</groupId>
        <artifactId>spring-ai-starter-model-openai</artifactId>
    </dependency>
    <!-- VectorStore PostgreSQL + pgvector -->
    <dependency>
        <groupId>org.springframework.ai</groupId>
        <artifactId>spring-ai-starter-vector-store-pgvector</artifactId>
    </dependency>
    <!-- Lecteurs : PDF, TIKA (DOCX, HTML, etc.) -->
    <dependency>
        <groupId>org.springframework.ai</groupId>
        <artifactId>spring-ai-pdf-document-reader</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.ai</groupId>
        <artifactId>spring-ai-tika-document-reader</artifactId>
    </dependency>
    <!-- Le rag-service expose aussi un serveur MCP -->
    <dependency>
        <groupId>org.springframework.ai</groupId>
        <artifactId>spring-ai-starter-mcp-server-webmvc</artifactId>
    </dependency>
</dependencies>
```

### `application.yml`

```yaml
server:
  port: 8091
spring:
  application:
    name: rag-service
  datasource:
    url: jdbc:postgresql://localhost:5432/ragdb
    username: rag
    password: rag
  ai:
    openai:
      api-key: ${OPENAI_API_KEY}
      embedding:
        options:
          model: text-embedding-3-small
    vectorstore:
      pgvector:
        index-type: HNSW
        distance-type: COSINE_DISTANCE
        dimensions: 1536
        initialize-schema: true
    mcp:
      server:
        name: rag-mcp-server
        version: 1.0.0
        type: SYNC
```

> Initialisation Postgres + pgvector (Docker) :
>
> ```bash
> docker run -d --name pgvector \
>   -e POSTGRES_DB=ragdb -e POSTGRES_USER=rag -e POSTGRES_PASSWORD=rag \
>   -p 5432:5432 pgvector/pgvector:pg16
> ```

## 2.3 Ingestion de documents

```java
package ma.enset.ragservice.ingestion;

import org.springframework.ai.document.Document;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.Resource;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.core.io.ByteArrayResource;
import java.util.List;

@RestController
@RequestMapping("/api/ingest")
public class IngestionController {

    private final VectorStore vectorStore;
    public IngestionController(VectorStore vs) { this.vectorStore = vs; }

    @PostMapping("/pdf")
    public int ingestPdf(@RequestParam("file") MultipartFile file) throws Exception {
        Resource res = new ByteArrayResource(file.getBytes()) {
            @Override public String getFilename() { return file.getOriginalFilename(); }
        };
        List<Document> docs = new PagePdfDocumentReader(res).get();
        List<Document> chunks = new TokenTextSplitter().apply(docs);
        chunks.forEach(d -> d.getMetadata().put("source", file.getOriginalFilename()));
        vectorStore.add(chunks);
        return chunks.size();
    }
}
```

Test :

```bash
curl -F "file=@catalogue-2026.pdf" http://localhost:8091/api/ingest/pdf
```

## 2.4 Exposition du RAG comme outil MCP

```java
package ma.enset.ragservice.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class RagTools {

    private final VectorStore vectorStore;
    public RagTools(VectorStore vs) { this.vectorStore = vs; }

    @Tool(description = """
        Recherche dans la base documentaire interne (manuels, catalogues, procédures).
        À utiliser pour toute question portant sur des informations propres à l'entreprise.
    """)
    public String searchKnowledgeBase(
        @ToolParam(description = "Question ou mots-clés à rechercher") String query) {

        var results = vectorStore.similaritySearch(
            SearchRequest.builder().query(query).topK(5).similarityThreshold(0.5).build());

        if (results == null || results.isEmpty())
            return "Aucun document pertinent trouvé.";

        return results.stream()
            .map(d -> "Source: " + d.getMetadata().getOrDefault("source", "n/a")
                    + "\n" + d.getText())
            .collect(Collectors.joining("\n---\n"));
    }
}
```

Enregistrement :

```java
@Bean
public ToolCallbackProvider ragToolProvider(RagTools tools) {
    return MethodToolCallbackProvider.builder().toolObjects(tools).build();
}
```

## 2.5 Intégration côté `chatbot-core`

Décommenter la connexion MCP supplémentaire :

```yaml
spring:
  ai:
    mcp:
      client:
        sse:
          connections:
            mcp-tools-server:
              url: http://localhost:8090
            rag-service:
              url: http://localhost:8091
```

Et enrichir le **system prompt** :

```java
.defaultSystem("""
    Tu es l'assistant interne d'ENSET. Pour toute question portant
    sur des informations métier (procédures, catalogues, documentation),
    utilise impérativement l'outil `searchKnowledgeBase` AVANT de répondre,
    et cite la source.
""")
```

> 💡 Alternative : utiliser le `QuestionAnswerAdvisor` de Spring AI directement dans `chatbot-core` (sans passer par MCP), ce qui simplifie l'architecture quand un seul service contient le VectorStore. La version MCP est privilégiée ici pour rester cohérent avec la Partie 1 et préparer la Partie 3 (services indépendants et scalables).

---

# 🏗️ PARTIE 3 — Architecture micro-services

## 3.1 Vue cible

```
                              ┌───────────────────────────────┐
                              │       Config Server (8888)    │
                              └──────────────┬────────────────┘
                                             │ pull config
        ┌────────────────────────────────────┴─────────────────────────────────┐
        ▼                                                                      ▼
┌────────────────┐  register / discover  ┌────────────────┐         ┌────────────────┐
│ Eureka (8761)  │ ◄───────────────────► │ chatbot-core   │         │  mcp-server    │
└────────────────┘                       └────────────────┘         └────────────────┘
        ▲                                       ▲                            ▲
        │                                       │                            │
        │      ┌────────────────┐    routing    │                            │
 Telegram ───► │ Gateway (8080) │ ──────────────┘                            │
   Webhook    │  (Spring Cloud)│ ───────────────────────────┐                │
              └────────┬───────┘                            ▼                │
                       │                            ┌────────────────┐       │
                       │                            │  rag-service   │ ──────┘
                       │                            └────────────────┘
                       │
              ┌────────▼───────┐
              │ Keycloak (8180)│  (OAuth2 / OIDC)
              └────────────────┘
```

Objectifs :

- **Découplage** : chaque service est versionné et déployé indépendamment.
- **Configuration centralisée** via Spring Cloud Config (Git).
- **Discovery** Eureka : `chatbot-core` ne connaît plus l'URL `http://localhost:8090` mais `lb://mcp-server`.
- **Gateway** unique en façade (webhooks Telegram, API admin, etc.).
- **Sécurité** : OAuth2 / JWT (Keycloak) pour les routes admin et l'ingestion RAG.
- **Observabilité** : Actuator + Micrometer + Prometheus + Grafana + Zipkin.

## 3.2 Config Server

`config-server/pom.xml` :

```xml
<dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>spring-cloud-config-server</artifactId>
</dependency>
```

`application.yml` :

```yaml
server.port: 8888
spring:
  application.name: config-server
  cloud:
    config:
      server:
        git:
          uri: https://github.com/<org>/chatbot-config-repo
          default-label: main
```

Activation :

```java
@SpringBootApplication
@EnableConfigServer
public class ConfigServerApplication { ... }
```

Côté chaque micro-service, ajouter :

```xml
<dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>spring-cloud-starter-config</artifactId>
</dependency>
```

et un `application.yml` minimal :

```yaml
spring:
  application.name: chatbot-core
  config:
    import: "optional:configserver:http://localhost:8888"
```

## 3.3 Service Discovery (Eureka)

`discovery-server` :

```xml
<dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>spring-cloud-starter-netflix-eureka-server</artifactId>
</dependency>
```

```java
@SpringBootApplication
@EnableEurekaServer
public class DiscoveryServerApplication { ... }
```

```yaml
server.port: 8761
eureka:
  client:
    register-with-eureka: false
    fetch-registry: false
```

Sur chaque service métier :

```xml
<dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>spring-cloud-starter-netflix-eureka-client</artifactId>
</dependency>
```

```yaml
eureka:
  client:
    service-url:
      defaultZone: http://localhost:8761/eureka/
```

Dans `chatbot-core`, les connexions MCP peuvent désormais cibler le nom logique des services (résolution via Spring Cloud LoadBalancer dans un MCP client custom, ou simplement en passant par la Gateway).

## 3.4 API Gateway

`gateway/pom.xml` :

```xml
<dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>spring-cloud-starter-gateway</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>spring-cloud-starter-netflix-eureka-client</artifactId>
</dependency>
```

`application.yml` :

```yaml
server.port: 8080
spring:
  application.name: gateway
  cloud:
    gateway:
      discovery:
        locator:
          enabled: true
          lower-case-service-id: true
      routes:
        - id: chatbot
          uri: lb://chatbot-core
          predicates:
            - Path=/api/chat/**
        - id: rag-ingest
          uri: lb://rag-service
          predicates:
            - Path=/api/ingest/**
          filters:
            - TokenRelay=
        - id: telegram-webhook
          uri: lb://telegram-client
          predicates:
            - Path=/telegram/webhook/**
```

> En production, basculer Telegram du mode long-polling vers **webhook** :
> `https://api.telegram.org/bot<token>/setWebhook?url=https://votre-domaine/telegram/webhook`.

## 3.5 Sécurité (Keycloak / OAuth2)

Lancer Keycloak :

```bash
docker run -d --name keycloak -p 8180:8080 \
  -e KEYCLOAK_ADMIN=admin -e KEYCLOAK_ADMIN_PASSWORD=admin \
  quay.io/keycloak/keycloak:26.0 start-dev
```

Sur la Gateway :

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-oauth2-resource-server</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>spring-cloud-starter-security</artifactId>
</dependency>
```

```yaml
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: http://localhost:8180/realms/chatbot
```

Configuration Spring Security (autoriser `/api/chat/**` mais protéger `/api/ingest/**`) :

```java
@Bean
public SecurityWebFilterChain security(ServerHttpSecurity http) {
    return http
        .authorizeExchange(ex -> ex
            .pathMatchers("/api/chat/**", "/telegram/webhook/**").permitAll()
            .pathMatchers("/api/ingest/**").hasRole("ADMIN")
            .anyExchange().authenticated())
        .oauth2ResourceServer(o -> o.jwt(Customizer.withDefaults()))
        .build();
}
```

## 3.6 Observabilité

Sur **chaque** service :

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-registry-prometheus</artifactId>
</dependency>
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-tracing-bridge-otel</artifactId>
</dependency>
<dependency>
    <groupId>io.opentelemetry</groupId>
    <artifactId>opentelemetry-exporter-zipkin</artifactId>
</dependency>
```

```yaml
management:
  endpoints.web.exposure.include: "*"
  tracing.sampling.probability: 1.0
  zipkin.tracing.endpoint: http://localhost:9411/api/v2/spans
```

Spring AI fournit nativement des métriques `gen_ai.client.*` (tokens, latence, coûts) très utiles dans Grafana.

## 3.7 Docker Compose & déploiement

`docker-compose.yml` (extrait simplifié) :

```yaml
services:
  pgvector:
    image: pgvector/pgvector:pg16
    environment:
      POSTGRES_DB: ragdb
      POSTGRES_USER: rag
      POSTGRES_PASSWORD: rag
    ports: ["5432:5432"]

  keycloak:
    image: quay.io/keycloak/keycloak:26.0
    command: start-dev
    environment:
      KEYCLOAK_ADMIN: admin
      KEYCLOAK_ADMIN_PASSWORD: admin
    ports: ["8180:8080"]

  discovery:
    build: ./discovery-server
    ports: ["8761:8761"]

  config-server:
    build: ./config-server
    ports: ["8888:8888"]

  mcp-server:
    build: ./mcp-server
    depends_on: [discovery, config-server]

  rag-service:
    build: ./rag-service
    depends_on: [pgvector, discovery, config-server]
    environment:
      OPENAI_API_KEY: ${OPENAI_API_KEY}

  chatbot-core:
    build: ./chatbot-core
    depends_on: [mcp-server, rag-service, discovery]
    environment:
      OPENAI_API_KEY: ${OPENAI_API_KEY}

  telegram-client:
    build: ./telegram-client
    depends_on: [chatbot-core]
    environment:
      TELEGRAM_BOT_TOKEN: ${TELEGRAM_BOT_TOKEN}

  gateway:
    build: ./gateway
    ports: ["8080:8080"]
    depends_on: [chatbot-core, rag-service, telegram-client, keycloak]
```

Démarrage :

```bash
export OPENAI_API_KEY=sk-...
export TELEGRAM_BOT_TOKEN=123:AAH...
docker compose up --build
```

---

## 🗺️ Roadmap & ressources

### Améliorations possibles

- **Mémoire conversationnelle persistante** par `chatId` Telegram (Redis / JDBC).
- **Streaming** des réponses LLM côté Telegram (envoi progressif + édition de message).
- **Multimodal** : prise en charge des images Telegram via Spring AI Vision.
- **Évaluation RAG** avec `RelevancyEvaluator` / `FactCheckingEvaluator` de Spring AI.
- **Cache sémantique** des réponses (redis-stack + similarité d'embedding).
- **Rate limiting** via Resilience4j sur la Gateway.

### Liens utiles

- Spring AI — Documentation officielle : <https://docs.spring.io/spring-ai/reference/>
- Model Context Protocol — Spec : <https://modelcontextprotocol.io>
- Spring AI MCP — Référence : <https://docs.spring.io/spring-ai/reference/api/mcp/>
- Telegram Bots (Java lib) : <https://github.com/rubenlagus/TelegramBots>
- Vidéo source (Mohamed Youssfi) : <https://www.youtube.com/watch?v=Q12plqwksxk>
- Repo Partie 1 : <https://github.com/mohamedYoussfi/chatbot-spring-ai-mcp-telegram-client>

---

> ✍️ **Licence** : code d'exemple sous MIT.
> Pull requests et issues bienvenues.
