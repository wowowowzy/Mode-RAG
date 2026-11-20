package com.challenger.moderoute.mapper;

import com.challenger.moderoute.common.util.LibraryVersion;
import com.challenger.moderoute.entity.Card;
import com.challenger.moderoute.entity.CardRelations;
import com.challenger.moderoute.entity.enums.CardDomain;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 卡片加载与逐字段校验。任何一条不合规都直接拒绝启动：
 * 库是只读快照，宁可起不来，也不要带着半份库对外服务。
 */
public final class CardLoader {

    private static final Set<String> ALLOWED_KEYS = Set.of(
            "id", "domain", "name", "applicableWhen", "steps", "failureModes", "exitConditions",
            "outputContract", "taxonomy", "relations");

    private static final int MAX_APPLICABLE_WHEN = 80;

    private CardLoader() {
    }

    public static List<Card> load(Path cardsDir) {
        List<Path> files = LibraryVersion.listCardFiles(cardsDir);
        if (files.isEmpty()) {
            throw new IllegalStateException("卡片目录为空: " + cardsDir.toAbsolutePath());
        }

        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        Yaml yaml = new Yaml(new SafeConstructor(options));

        List<Card> cards = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        Map<String, Path> seenIds = new LinkedHashMap<>();

        for (Path file : files) {
            String relative = LibraryVersion.relativePath(cardsDir, file);
            String content = LibraryVersion.readUtf8(file);
            Map<String, Object> raw;
            try {
                Object loaded = yaml.load(content);
                if (!(loaded instanceof Map<?, ?> map)) {
                    errors.add(relative + ": 顶层结构必须是 YAML 映射");
                    continue;
                }
                raw = toStringMap(map);
            } catch (RuntimeException e) {
                errors.add(relative + ": YAML 解析失败 - " + e.getMessage());
                continue;
            }

            List<String> fileErrors = new ArrayList<>();
            Card card = parse(raw, relative, fileErrors);
            if (!fileErrors.isEmpty()) {
                errors.addAll(fileErrors);
                continue;
            }
            Path previous = seenIds.putIfAbsent(card.id(), file);
            if (previous != null) {
                errors.add(relative + ": 卡片 id 重复 " + card.id()
                        + "（已存在于 " + LibraryVersion.relativePath(cardsDir, previous) + "）");
                continue;
            }
            String folder = folderOf(cardsDir, file);
            if (!folder.equals(card.domain().key())) {
                errors.add(relative + ": 文件所在目录 " + folder + " 与 domain " + card.domain().key() + " 不一致");
                continue;
            }
            cards.add(card);
        }

        if (!errors.isEmpty()) {
            throw new IllegalStateException("模式库校验失败（" + errors.size() + " 处）:\n  - "
                    + String.join("\n  - ", errors));
        }
        return List.copyOf(cards);
    }

    private static String folderOf(Path root, Path file) {
        Path relative = root.relativize(file);
        return relative.getNameCount() > 1 ? relative.getName(0).toString() : "";
    }

    private static Card parse(Map<String, Object> raw, String where, List<String> errors) {
        for (String key : raw.keySet()) {
            if (!ALLOWED_KEYS.contains(key)) {
                errors.add(where + ": 未知字段 " + key);
            }
        }

        String id = requireString(raw, "id", where, errors);
        String domainKey = requireString(raw, "domain", where, errors);
        String name = requireString(raw, "name", where, errors);
        String applicableWhen = requireString(raw, "applicableWhen", where, errors);
        String outputContract = requireString(raw, "outputContract", where, errors);
        List<String> steps = requireStringList(raw, "steps", where, errors);
        List<String> failureModes = requireStringList(raw, "failureModes", where, errors);
        List<String> exitConditions = requireStringList(raw, "exitConditions", where, errors);

        CardDomain domain = null;
        if (domainKey != null) {
            try {
                domain = CardDomain.fromKey(domainKey);
            } catch (IllegalArgumentException e) {
                errors.add(where + ": domain 只能是 planning / debate / general，实际为 " + domainKey);
            }
        }

        if (id != null && domain != null) {
            if (!id.matches("^(planning|debate|general)-[a-z0-9]+(-[a-z0-9]+)*$")) {
                errors.add(where + ": id 必须形如 域前缀-策略名（小写字母、数字、连字符）: " + id);
            } else if (!id.startsWith(domain.key() + "-")) {
                errors.add(where + ": id 前缀必须与 domain 一致: " + id);
            }
        }

        if (applicableWhen != null) {
            int length = applicableWhen.codePointCount(0, applicableWhen.length());
            if (length > MAX_APPLICABLE_WHEN) {
                errors.add(where + ": applicableWhen 超过 " + MAX_APPLICABLE_WHEN + " 字（实际 " + length + "）");
            }
            if (!applicableWhen.contains("不适用于")) {
                errors.add(where + ": applicableWhen 必须包含「不适用于…」的负例说明");
            }
        }

        if (steps != null && (steps.size() < 4 || steps.size() > 6)) {
            errors.add(where + ": steps 必须是 4–6 条（实际 " + steps.size() + "）");
        }
        if (failureModes != null && (failureModes.size() < 2 || failureModes.size() > 4)) {
            errors.add(where + ": failureModes 必须是 2–4 条（实际 " + failureModes.size() + "）");
        }
        if (exitConditions != null && (exitConditions.size() < 1 || exitConditions.size() > 3)) {
            errors.add(where + ": exitConditions 必须是 1–3 条（实际 " + exitConditions.size() + "）");
        }

        String family = parseFamily(raw, where, errors);
        CardRelations relations = parseRelations(raw, where, errors);

        if (!errors.isEmpty()) {
            return null;
        }
        return new Card(id, domain, name, applicableWhen, steps, failureModes, exitConditions,
                outputContract, family, relations);
    }

    /** taxonomy.family 可选；缺省为 null（表示不属于任何约束族）。 */
    private static String parseFamily(Map<String, Object> raw, String where, List<String> errors) {
        Object taxonomy = raw.get("taxonomy");
        if (taxonomy == null) {
            return null;
        }
        if (!(taxonomy instanceof Map<?, ?> map)) {
            errors.add(where + ": taxonomy 必须是映射");
            return null;
        }
        Object family = toStringMap(map).get("family");
        if (family == null) {
            return null;
        }
        String text = String.valueOf(family).trim();
        if (text.isEmpty()) {
            errors.add(where + ": taxonomy.family 不能为空");
            return null;
        }
        if (!text.matches("[a-z][a-z0-9-]*")) {
            errors.add(where + ": taxonomy.family 只能含小写字母、数字与连字符: " + text);
            return null;
        }
        return text;
    }

    /** relations 可选；字段缺失视为空列表。所有 cardId 引用做存在性与格式校验。 */
    private static CardRelations parseRelations(Map<String, Object> raw, String where, List<String> errors) {
        Object value = raw.get("relations");
        if (value == null) {
            return CardRelations.EMPTY;
        }
        if (!(value instanceof Map<?, ?> map)) {
            errors.add(where + ": relations 必须是映射");
            return CardRelations.EMPTY;
        }
        Map<String, Object> node = toStringMap(map);
        String specializes = optionalString(node, "specializes", where, errors);
        List<String> excludes = optionalStringList(node, "excludes", where, errors);
        List<String> augments = optionalStringList(node, "augments", where, errors);
        List<String> requires = optionalStringList(node, "requires", where, errors);
        List<String> verifyWith = optionalStringList(node, "verifyWith", where, errors);

        java.util.List<String> refs = new java.util.ArrayList<>();
        if (specializes != null) {
            refs.add(specializes);
        }
        refs.addAll(excludes);
        refs.addAll(augments);
        refs.addAll(requires);
        refs.addAll(verifyWith);
        for (String ref : refs) {
            if (!ref.matches("^(planning|debate|general)-[a-z0-9]+(-[a-z0-9]+)*$")) {
                errors.add(where + ": relations 引用了非法 cardId: " + ref);
            }
        }
        if (!errors.isEmpty()) {
            return CardRelations.EMPTY;
        }
        return new CardRelations(specializes, excludes, augments, requires, verifyWith);
    }

    private static String optionalString(Map<String, Object> raw, String key, String where, List<String> errors) {
        Object value = raw.get(key);
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        if (text.isEmpty()) {
            errors.add(where + ": relations." + key + " 不能为空");
            return null;
        }
        return text;
    }

    private static List<String> optionalStringList(Map<String, Object> raw, String key,
                                                   String where, List<String> errors) {
        Object value = raw.get(key);
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> list)) {
            errors.add(where + ": relations." + key + " 必须是列表");
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (Object item : list) {
            String text = item == null ? "" : String.valueOf(item).trim();
            if (text.isEmpty()) {
                errors.add(where + ": relations." + key + " 含空元素");
            } else {
                out.add(text);
            }
        }
        return List.copyOf(out);
    }

    private static String requireString(Map<String, Object> raw, String key, String where, List<String> errors) {
        Object value = raw.get(key);
        if (value == null) {
            errors.add(where + ": 缺少字段 " + key);
            return null;
        }
        String text = String.valueOf(value).trim();
        if (text.isEmpty()) {
            errors.add(where + ": 字段 " + key + " 不能为空");
            return null;
        }
        return text;
    }

    private static List<String> requireStringList(Map<String, Object> raw, String key, String where, List<String> errors) {
        Object value = raw.get(key);
        if (value == null) {
            errors.add(where + ": 缺少字段 " + key);
            return null;
        }
        if (!(value instanceof List<?> list)) {
            errors.add(where + ": 字段 " + key + " 必须是列表");
            return null;
        }
        List<String> out = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            Object item = list.get(i);
            String text = item == null ? "" : String.valueOf(item).trim();
            if (text.isEmpty()) {
                errors.add(where + ": 字段 " + key + " 第 " + (i + 1) + " 条为空");
                continue;
            }
            out.add(text);
        }
        return List.copyOf(out);
    }

    private static Map<String, Object> toStringMap(Map<?, ?> map) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            out.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return out;
    }
}
