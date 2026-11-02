package com.challenger.modetest.client;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 执行模型调用。两条臂共用同一个 ChatClient，只有 system 段不同。
 */
@Component
public class ChatGateway {

    private static final Logger log = LoggerFactory.getLogger(ChatGateway.class);

    private final ChatClient chatClient;

    public ChatGateway(ChatClient.Builder builder,
                       @Value("${spring.ai.openai.chat.options.model:unknown}") String model,
                       @Value("${spring.ai.openai.base-url:}") String baseUrl,
                       @Value("${spring.ai.openai.api-key:}") String apiKey) {
        this.chatClient = builder.build();
        // spring.config.import 用的是 optional: 前缀，路径不存在时不报错、只是不导入，
        // 结果就是配置为空、第一次调模型才 401。这里提前喊出来。
        if (baseUrl == null || baseUrl.isBlank()) {
            log.error("网关地址为空 —— 请确认 ModeRouter 与 ModeTest 位于同一父目录"
                    + "（需要 ../ModeRouter/.env.local），或在 ModeTest/.env.local 中直接填 OPENAI_BASE_URL");
        }
        if (apiKey == null || apiKey.isBlank()) {
            log.error("API key 为空 —— 同上；未配置时模型调用会返回 401");
        }
        log.info("执行模型：{} · 网关 {}", model,
                baseUrl == null || baseUrl.isBlank() ? "(未配置)" : baseUrl);
    }

    /**
     * @param systemPrompt 为空时等价于不注入骨架（baseline 臂）
     */
    public ChatAnswer answer(String systemPrompt, String task) {
        long start = System.nanoTime();
        ChatClient.ChatClientRequestSpec spec = chatClient.prompt();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            spec = spec.system(systemPrompt);
        }
        ChatResponse response = spec.user(task).call().chatResponse();
        long latencyMs = (System.nanoTime() - start) / 1_000_000L;

        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            throw new ChatException("执行模型返回为空");
        }
        String text = response.getResult().getOutput().getText();
        Usage usage = response.getMetadata() == null ? null : response.getMetadata().getUsage();
        Integer promptTokens = usage == null ? null : usage.getPromptTokens();
        Integer completionTokens = usage == null ? null : usage.getCompletionTokens();
        return new ChatAnswer(text, promptTokens, completionTokens, latencyMs);
    }

    public record ChatAnswer(String text, Integer promptTokens, Integer completionTokens, long latencyMs) {
    }

    public static class ChatException extends RuntimeException {
        public ChatException(String message) {
            super(message);
        }

        public ChatException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
