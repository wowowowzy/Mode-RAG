package com.challenger.moderoute.card;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;

/**
 * 模式库版本：按相对路径排序、内容归一化后拼接，取 SHA-256 前 12 位。
 * 归一化不可省略，否则 Windows 与 Linux 会算出不同版本号。
 */
public final class LibraryVersion {

    private LibraryVersion() {
    }

    public static String compute(Path cardsDir) {
        List<Path> files = listCardFiles(cardsDir);
        StringBuilder sb = new StringBuilder();
        for (Path file : files) {
            sb.append(normalize(readUtf8(file)));
        }
        return sha256Hex(sb.toString()).substring(0, 12);
    }

    public static List<Path> listCardFiles(Path cardsDir) {
        if (!Files.isDirectory(cardsDir)) {
            throw new IllegalStateException("卡片目录不存在: " + cardsDir.toAbsolutePath());
        }
        try (Stream<Path> stream = Files.walk(cardsDir)) {
            List<Path> files = new ArrayList<>(stream
                    .filter(Files::isRegularFile)
                    .filter(p -> {
                        String name = p.getFileName().toString().toLowerCase();
                        return name.endsWith(".yaml") || name.endsWith(".yml");
                    })
                    .toList());
            files.sort(Comparator.comparing(p -> relativePath(cardsDir, p)));
            return files;
        } catch (IOException e) {
            throw new IllegalStateException("扫描卡片目录失败: " + cardsDir, e);
        }
    }

    /** 统一用 / 分隔，保证跨平台排序一致。 */
    public static String relativePath(Path root, Path file) {
        return root.relativize(file).toString().replace('\\', '/');
    }

    static String readUtf8(Path file) {
        try {
            return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读取卡片文件失败: " + file, e);
        }
    }

    /** 去 BOM、行尾统一为 \n、去掉每行行尾空白。 */
    public static String normalize(String raw) {
        String text = raw.startsWith("\uFEFF") ? raw.substring(1) : raw;
        text = text.replace("\r\n", "\n").replace('\r', '\n');
        String[] lines = text.split("\n", -1);
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < lines.length; i++) {
            sb.append(stripTrailingWhitespace(lines[i]));
            sb.append('\n');
        }
        return sb.toString();
    }

    private static String stripTrailingWhitespace(String line) {
        int end = line.length();
        while (end > 0) {
            char c = line.charAt(end - 1);
            if (c == ' ' || c == '\t' || c == '\u00A0' || c == '\u3000') {
                end--;
            } else {
                break;
            }
        }
        return line.substring(0, end);
    }

    static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 缺少 SHA-256", e);
        }
    }
}
