package com.challenger.moderoute.entity;

import com.challenger.moderoute.entity.enums.CardDomain;

import java.util.List;

/**
 * 一张模式卡：一段可以直接拼进 prompt 的推理骨架。
 * family / relations 来自 YAML 的 taxonomy / relations 字段，属于不可变快照的一部分。
 */
public record Card(
        String id,
        CardDomain domain,
        String name,
        String applicableWhen,
        List<String> steps,
        List<String> failureModes,
        List<String> exitConditions,
        String outputContract,
        String family,
        CardRelations relations) {

    /** 兼容旧调用点：没有 taxonomy / relations 的卡按「无族、无关系」处理。 */
    public Card(String id, CardDomain domain, String name, String applicableWhen,
                List<String> steps, List<String> failureModes, List<String> exitConditions,
                String outputContract) {
        this(id, domain, name, applicableWhen, steps, failureModes, exitConditions,
                outputContract, null, CardRelations.EMPTY);
    }

    public Card {
        steps = List.copyOf(steps);
        failureModes = List.copyOf(failureModes);
        exitConditions = List.copyOf(exitConditions);
        relations = relations == null ? CardRelations.EMPTY : relations;
    }

    /**
     * 参与召回的文本。默认只用适用条件的正面部分：负例是给「排除」用的，
     * 混进相似度会把它变成正向信号。
     */
    public String recallText(boolean stripNegativeClause) {
        return stripNegativeClause ? positiveApplicableWhen(applicableWhen) : applicableWhen;
    }

    public static String positiveApplicableWhen(String applicableWhen) {
        int idx = applicableWhen.indexOf("不适用于");
        if (idx <= 0) {
            return applicableWhen.trim();
        }
        return stripTrailingSeparators(applicableWhen.substring(0, idx));
    }

    private static String stripTrailingSeparators(String s) {
        int end = s.length();
        while (end > 0) {
            char c = s.charAt(end - 1);
            if (c == '；' || c == ';' || c == '，' || c == ',' || c == '。' || c == '、'
                    || c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                end--;
            } else {
                break;
            }
        }
        return s.substring(0, end).trim();
    }
}
