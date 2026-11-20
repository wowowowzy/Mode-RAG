package com.challenger.moderoute.entity;

import java.util.List;

/**
 * 卡片关系（P2 关系图，不引入任何图框架）。
 * 每条边都有确定的方向，节点集可数，便于确定性排序与审计。
 */
public record CardRelations(
        String specializes,
        List<String> excludes,
        List<String> augments,
        List<String> requires,
        List<String> verifyWith) {

    public static final CardRelations EMPTY =
            new CardRelations(null, List.of(), List.of(), List.of(), List.of());

    public CardRelations {
        excludes = excludes == null ? List.of() : List.copyOf(excludes);
        augments = augments == null ? List.of() : List.copyOf(augments);
        requires = requires == null ? List.of() : List.copyOf(requires);
        verifyWith = verifyWith == null ? List.of() : List.copyOf(verifyWith);
    }

    /** 该卡在关系图里贡献的节点数（自身 1 + 相邻节点去重后计数）。 */
    public int nodeCount() {
        java.util.LinkedHashSet<String> nodes = new java.util.LinkedHashSet<>();
        if (specializes != null && !specializes.isBlank()) {
            nodes.add(specializes);
        }
        nodes.addAll(excludes);
        nodes.addAll(augments);
        nodes.addAll(requires);
        nodes.addAll(verifyWith);
        return 1 + nodes.size();
    }
}
