package com.challenger.moderoute.card;

import java.util.Arrays;

public enum CardDomain {

    PLANNING("planning", "plan_v1", """
            按 plan_v1 输出 JSON：{"assignments":[{"order","line","day","startHour","hours"}],"verification":[{"cid","passed","detail"}]}
            """),

    DEBATE("debate", "debate_v1", """
            按 debate_v1 输出 JSON：{"stances":[{"label","thesis","evidence","objections","concessions"}],"verdict"}
            """),

    GENERAL("general", null, "");

    private final String key;
    private final String outputFormatName;
    private final String outputFormatBlock;

    CardDomain(String key, String outputFormatName, String outputFormatBlock) {
        this.key = key;
        this.outputFormatName = outputFormatName;
        this.outputFormatBlock = outputFormatBlock;
    }

    public String key() {
        return key;
    }

    /** plan_v1 / debate_v1，general 域没有机械核验格式。 */
    public String outputFormatName() {
        return outputFormatName;
    }

    public String outputFormatBlock() {
        return outputFormatBlock;
    }

    public static CardDomain fromKey(String key) {
        return Arrays.stream(values())
                .filter(d -> d.key.equals(key))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("未知域: " + key));
    }
}
