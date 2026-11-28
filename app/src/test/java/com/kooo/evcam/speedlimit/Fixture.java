/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.speedlimit;

import static org.junit.Assert.assertNotNull;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * 测试资源 speedlimit/：参考打包器（项目本地文档 speed-limits/zslp.py vectors）生成的一个包（假造的路，不是 OSM 数据）
 * 和它的用例（解开后的每一块、手写的匹配场景）。
 */
final class Fixture {

    private Fixture() {
    }

    static byte[] bytes() throws IOException {
        return read("/speedlimit/fixture.zslp");
    }

    static JsonObject vectors() throws IOException {
        return JsonParser.parseString(new String(read("/speedlimit/vectors.json"), StandardCharsets.UTF_8))
                .getAsJsonObject();
    }

    /** 把这些字节写成一个临时文件。 */
    static File write(File dir, String name, byte[] data) throws IOException {
        File f = new File(dir, name);
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(data);
        }
        return f;
    }

    /** 包头里的国家换成别的（结构照旧合法，sha256 就变了）。 */
    static byte[] withCountry(byte[] data, String country) {
        byte[] copy = data.clone();
        copy[6] = (byte) country.charAt(0);
        copy[7] = (byte) country.charAt(1);
        return copy;
    }

    private static byte[] read(String path) throws IOException {
        try (InputStream in = Fixture.class.getResourceAsStream(path)) {
            assertNotNull(path, in);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int n;
            while ((n = in.read(chunk)) != -1) {
                out.write(chunk, 0, n);
            }
            return out.toByteArray();
        }
    }
}
