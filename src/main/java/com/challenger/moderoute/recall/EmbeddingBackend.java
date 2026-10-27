package com.challenger.moderoute.recall;

import org.springframework.ai.embedding.EmbeddingModel;

/**
 * 向量后端：Spring AI 的 EmbeddingModel 抽象 + 一个用于缓存键与审计的稳定名字。
 */
public record EmbeddingBackend(EmbeddingModel model, String name) {
}
