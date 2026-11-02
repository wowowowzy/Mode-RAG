package com.challenger.moderoute.common.util;

import com.challenger.moderoute.common.util.LibraryVersion;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class LibraryVersionTest {

    @TempDir
    Path dir;

    @Test
    void 行尾与行尾空白不影响版本号() throws Exception {
        Files.writeString(dir.resolve("a.yaml"), "id: a\nname: x\n", StandardCharsets.UTF_8);
        String lf = LibraryVersion.compute(dir);

        Files.writeString(dir.resolve("a.yaml"), "id: a \r\nname: x\t\r\n", StandardCharsets.UTF_8);
        String crlf = LibraryVersion.compute(dir);

        assertThat(crlf).isEqualTo(lf);
    }

    @Test
    void 内容变化会改变版本号() throws Exception {
        Files.writeString(dir.resolve("a.yaml"), "id: a\n", StandardCharsets.UTF_8);
        String first = LibraryVersion.compute(dir);
        Files.writeString(dir.resolve("a.yaml"), "id: b\n", StandardCharsets.UTF_8);
        assertThat(LibraryVersion.compute(dir)).isNotEqualTo(first);
    }

    @Test
    void 文件名顺序不参与哈希但决定拼接顺序() throws Exception {
        Files.writeString(dir.resolve("b.yaml"), "B\n", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("a.yaml"), "A\n", StandardCharsets.UTF_8);
        String version = LibraryVersion.compute(dir);

        Files.delete(dir.resolve("b.yaml"));
        Files.delete(dir.resolve("a.yaml"));
        Files.writeString(dir.resolve("x.yaml"), "A\n", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("y.yaml"), "B\n", StandardCharsets.UTF_8);

        assertThat(LibraryVersion.compute(dir)).isEqualTo(version);
    }

    @Test
    void BOM不参与归一化() throws Exception {
        Files.writeString(dir.resolve("a.yaml"), "id: a\n", StandardCharsets.UTF_8);
        String plain = LibraryVersion.compute(dir);
        Files.writeString(dir.resolve("a.yaml"), "\uFEFFid: a\n", StandardCharsets.UTF_8);
        assertThat(LibraryVersion.compute(dir)).isEqualTo(plain);
    }
}
