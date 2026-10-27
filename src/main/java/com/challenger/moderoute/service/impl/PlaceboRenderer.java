package com.challenger.moderoute.service.impl;

import com.challenger.moderoute.entity.enums.CardDomain;

import com.challenger.moderoute.entity.enums.CardDomain;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * placebo 臂：同结构、内容通用的假骨架，长度向真实注入文本对齐。
 * 「注入了针对性骨架」和「prompt 变长了」必须分开，否则会高估增益。
 */
@Component
public class PlaceboRenderer {

    private static final List<String> PADDING = List.of(
            "补充自查：确认每一步都能追溯到任务中写明的信息。",
            "补充自查：若有两条路径都说得通，说明取舍依据。",
            "补充自查：把假设单独列出，不要混进结论。",
            "补充自查：检查是否漏掉边界情况。",
            "补充自查：对最终结论给出可被反驳的理由。");

    private static final String BASE = """
            【推理骨架：通用分步推进】

            适用条件：任何需要多步推进的任务。

            按顺序执行：
            1. 复述任务要求，列出必须满足的条件。
            2. 区分已知信息与仍然缺失的信息。
            3. 选择一种通用的推进顺序，逐步展开。
            4. 每推进一步回到条件清单核对是否满足。
            5. 汇总结论，并说明仍不确定的部分。

            常见失败模式（逐条自查）：
            - 漏掉任务中明确写出的条件。
            - 结论与过程不一致。

            停止条件（满足即停止并说明，不要硬给结论）：
            - 关键信息缺失且无法从任务推出时，列出所需信息后停止。

            输出格式：
            先给结论，再给依据与不确定性说明。
            """;

    public String render(CardDomain domain, int targetChars) {
        String text = BASE;
        if (targetChars > text.length()) {
            StringBuilder sb = new StringBuilder(text);
            int i = 0;
            while (sb.length() < targetChars) {
                sb.append('\n').append(PADDING.get(i % PADDING.size()));
                i++;
            }
            text = sb.toString();
        }
        if (targetChars > 0 && targetChars < text.length()) {
            return cutAtLine(text, targetChars);
        }
        return text;
    }

    private static String cutAtLine(String text, int target) {
        int cut = text.lastIndexOf('\n', target);
        if (cut <= 0) {
            cut = target;
        }
        return text.substring(0, cut).stripTrailing();
    }
}
