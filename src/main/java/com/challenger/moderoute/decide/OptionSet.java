package com.challenger.moderoute.decide;

import com.challenger.moderoute.recall.ScoredCard;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 精判选项：A–Z 单字母，最后一位留给「都不合适」。
 *
 * 字母顺序按 cardId 字典序，不按召回分数——分数会随 embedding 版本变化，
 * 按分数排会让同一任务在不同版本下拿到不同字母，破坏可复现性。
 */
public final class OptionSet {

    public static final String NONE = "None";
    public static final String NONE_DESCRIPTION = "None —— 以上骨架都不适用于该任务，本次不注入任何骨架";

    public record Option(char letter, String cardId) {
        public boolean isNone() {
            return cardId == null;
        }
    }

    private final List<Option> options;

    private OptionSet(List<Option> options) {
        this.options = List.copyOf(options);
    }

    public static OptionSet ofCandidates(List<ScoredCard> candidates) {
        List<ScoredCard> sorted = candidates.stream()
                .sorted(Comparator.comparing(ScoredCard::cardId))
                .toList();
        if (sorted.size() > 25) {
            throw new IllegalArgumentException("精判候选不能超过 25 张（另留一位给 None）");
        }
        List<String> ids = sorted.stream().map(ScoredCard::cardId).toList();
        return ofCardIds(ids);
    }

    public static OptionSet ofCardIds(List<String> cardIds) {
        List<String> sorted = cardIds.stream().sorted().toList();
        if (sorted.isEmpty()) {
            throw new IllegalArgumentException("候选不能为空");
        }
        if (sorted.size() > 25) {
            throw new IllegalArgumentException("精判候选不能超过 25 张（另留一位给 None）");
        }
        List<Option> options = new ArrayList<>(sorted.size() + 1);
        for (int i = 0; i < sorted.size(); i++) {
            options.add(new Option((char) ('A' + i), sorted.get(i)));
        }
        options.add(new Option((char) ('A' + sorted.size()), null));
        return new OptionSet(options);
    }

    public List<Option> options() {
        return options;
    }

    public int size() {
        return options.size();
    }

    public char noneLetter() {
        return options.get(options.size() - 1).letter();
    }

    public boolean isNone(char letter) {
        return letter == noneLetter();
    }

    public Optional<Option> byLetter(char letter) {
        int index = indexOf(letter);
        return index < 0 ? Optional.empty() : Optional.of(options.get(index));
    }

    public int indexOf(char letter) {
        for (int i = 0; i < options.size(); i++) {
            if (options.get(i).letter() == letter) {
                return i;
            }
        }
        return -1;
    }

    public Optional<String> cardIdOf(char letter) {
        return byLetter(letter).map(Option::cardId);
    }
}
