package com.challenger.moderoute.mapper;

import com.challenger.moderoute.common.util.LibraryVersion;
import com.challenger.moderoute.entity.ScoredCard;

import java.util.List;

/**
 * 向量存储。只有两件事：给定库版本 + 模型，判断向量是否已就绪；按余弦距离取 top-k。
 *
 * 实现必须满足一条不变量：删除存储后重启，服务行为完全不变（只会重新算一次向量）。
 * 所以任何「版本 / 模型 / 卡片集合 / 维度」不匹配，都必须当作"没有"，而不是"将就用"。
 */
public interface VectorMapper {

    /** sqlite / json，用于健康检查与排障。 */
    String kind();

    /**
     * 向量是否与当前库快照完全匹配。
     *
     * @param cardIds 按 cardId 升序，与库快照同序
     */
    boolean ready(String libraryVersion, String model, List<String> cardIds);

    /** 返回按余弦相似度降序的前 limit 张卡；调用前应已确认 ready()。 */
    List<ScoredCard> topK(String libraryVersion, String model, float[] query, int limit);

    /**
     * 持久化向量。
     *
     * @return true 表示已落盘；false 表示仅内存可用，重启后需要重算。
     *         实现不应抛异常——持久化失败是「可降级」而不是「致命」。
     */
    boolean save(String libraryVersion, String model, List<String> cardIds, List<float[]> vectors);
}
