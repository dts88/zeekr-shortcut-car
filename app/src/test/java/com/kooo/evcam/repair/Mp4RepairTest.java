package com.kooo.evcam.repair;

import static com.kooo.evcam.repair.Mp4Repair.box;
import static com.kooo.evcam.repair.Mp4Repair.cat;
import static com.kooo.evcam.repair.Mp4Repair.fullBox;
import static com.kooo.evcam.repair.Mp4Repair.readType;
import static com.kooo.evcam.repair.Mp4Repair.readU32;
import static com.kooo.evcam.repair.Mp4Repair.readU64;
import static com.kooo.evcam.repair.Mp4Repair.type;
import static com.kooo.evcam.repair.Mp4Repair.u16;
import static com.kooo.evcam.repair.Mp4Repair.u32;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * 断电留下的半截 MP4，补完索引之后要真的能读回来。
 *
 * <h3>为什么这条测试值得写</h3>
 *
 * <p>{@link Mp4Repair} 是<b>直接改用户文件</b>的代码，而且改的正是断电那一刻抢下来的
 * 那一段 —— 最不能再出事的一份。它又没法靠上车试：要造出一个没封口的文件，得在录制中途
 * 真的断一次电。</p>
 *
 * <p>所以这里自己造一个：按 MPEG4Writer 的写法拼出「ftyp + 预留的 free + 占位长度的 mdat
 * + 一串带长度前缀的 NAL」，再让修复跑一遍，然后<b>把补出来的索引重新解析一遍</b>，
 * 逐帧比对位置、大小和关键帧位置。造文件和读文件是两套代码，对上了才算数。</p>
 */
public class Mp4RepairTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private static final long TIMESCALE = 90000;
    private static final int FRAMES = 30;

    /** 每帧的字节数（含 4 字节长度前缀），造文件时记下来，验索引时用。 */
    private final List<long[]> expected = new ArrayList<>();  // {偏移, 大小, 是否关键帧}
    /** 夹在画面中间的声音块一共多少字节（录着声音的文件）。 */
    private long soundBytes;

    // ================================================================= 整个来回

    @Test
    public void rebuildsAnIndexThatMatchesTheFramesOnDisk() throws IOException {
        File reference = writeReference(folder.newFile("20250101_120000_front.mp4"));
        File broken = writeBroken(folder.newFile("20250101_120100_front.mp4"));
        long brokenLength = broken.length();

        Mp4Repair.Scan before = Mp4Repair.scan(broken);
        assertEquals(Mp4Repair.Status.REPAIRABLE, before.status);
        assertEquals(Mp4Repair.Status.HEALTHY, Mp4Repair.scan(reference).status);

        Mp4Repair.Template template = Mp4Repair.templateFrom(reference);
        // stts 是 (29 帧 × 3000) + (1 帧 × 6000)，平均 3100 —— 帧率不稳时按平均算
        assertEquals(TIMESCALE, template.timescale);
        assertEquals(3100, template.sampleTicks);
        assertEquals(1920, template.width);
        assertEquals(1080, template.height);
        assertTrue(!template.hevc);

        Mp4Repair.Repaired repaired = Mp4Repair.repair(broken, template, null);
        assertEquals("多出来的那个条带属于同一帧，不该多算一帧", FRAMES, repaired.samples);
        assertEquals("每 10 帧一个 IDR", 3, repaired.syncSamples);
        assertEquals(FRAMES * 3100L * 1000 / TIMESCALE, repaired.durationMs);
        assertEquals("结尾写了一半的那一帧要丢掉", 14, repaired.tailIgnored);

        assertEquals(Mp4Repair.Status.HEALTHY, Mp4Repair.scan(broken).status);
        assertTrue("索引是追加上去的，文件只会变长", broken.length() > brokenLength);

        // 补出来的索引，逐帧对一遍
        byte[] file = Files.readAllBytes(broken.toPath());
        byte[] moov = child(file, 0, file.length, "moov");
        byte[] stbl = path(moov, "trak", "mdia", "minf", "stbl");
        checkSampleTable(stbl);

        // mdat 的长度要正好停在索引前面，不能把索引也算进画面数据里
        long[] mdat = boxAt(file, "mdat");
        assertEquals(brokenLength, mdat[0] + mdat[1]);

        // 抄过去的解码参数要和参考文件一模一样
        Mp4Repair.Template after = Mp4Repair.templateFrom(broken);
        assertArrayEquals(template.stsd, after.stsd);
        assertEquals(template.timescale, after.timescale);
        assertEquals(3100, after.sampleTicks);
    }

    // ================================================================= 录着声音的文件

    /**
     * 环视那一路录着声音时，mdat 里画面一块、声音一块交替着。声音没有长度前缀：走到它就接不上了，
     * 要找到下一块画面接着走 —— 一帧都不能少，位置、大小一个字节不差，声音那几块整块跳过。
     */
    @Test
    public void skipsTheSoundBlocksBetweenPictureBlocks() throws IOException {
        File reference = writeReference(folder.newFile("20250101_120000_surround.mp4"));
        File broken = writeBroken(folder.newFile("20250101_120100_surround.mp4"), 5, false);

        Mp4Repair.Repaired repaired = Mp4Repair.repair(broken, Mp4Repair.templateFrom(reference), null);
        assertEquals(FRAMES, repaired.samples);
        assertEquals(3, repaired.syncSamples);
        assertEquals("声音那几块整块跳过", soundBytes, repaired.otherBytes);
        assertEquals("结尾写了一半的那一帧照样丢掉", 14, repaired.tailIgnored);

        byte[] file = Files.readAllBytes(broken.toPath());
        checkSampleTable(path(child(file, 0, file.length, "moov"), "trak", "mdia", "minf", "stbl"));
    }

    /**
     * 一块声音的开头碰巧像一个 NAL，长度又落在文件里：照它走会一口跳过后面好几帧画面。
     * 它后面接不上，在它自己那一截里能找到下一块画面 —— 所以它是假的，从那块画面接着走。
     */
    @Test
    public void aSoundBlockThatStartsLikeANalDoesNotSwallowThePicturesAfterIt() throws IOException {
        File reference = writeReference(folder.newFile("20250101_120000_surround.mp4"));
        File broken = writeBroken(folder.newFile("20250101_120100_surround.mp4"), 5, true);

        Mp4Repair.Repaired repaired = Mp4Repair.repair(broken, Mp4Repair.templateFrom(reference), null);
        assertEquals(FRAMES, repaired.samples);
        assertEquals(soundBytes, repaired.otherBytes);

        byte[] file = Files.readAllBytes(broken.toPath());
        checkSampleTable(path(child(file, 0, file.length, "moov"), "trak", "mdia", "minf", "stbl"));
    }

    /** HEVC 的 NAL 头：层号要是 0、时域号不能是 0、禁止位是 0、类型是编码器会出的那几种。 */
    @Test
    public void hevcNalHeadersMustLookLikeAnEncoderMadeThem() throws IOException {
        // 长度 4：两字节头 + 两字节内容
        assertNotNull("IDR_W_RADL", readOne(new byte[]{0, 0, 0, 4, 0x26, 0x01, (byte) 0x80, 0}, true));
        assertNotNull("TRAIL_R", readOne(new byte[]{0, 0, 0, 4, 0x02, 0x01, (byte) 0x80, 0}, true));
        assertNotNull("VPS", readOne(new byte[]{0, 0, 0, 4, 0x40, 0x01, 0x0C, 0}, true));
        assertNull("层号不是 0", readOne(new byte[]{0, 0, 0, 4, 0x26, 0x09, (byte) 0x80, 0}, true));
        assertNull("时域号是 0", readOne(new byte[]{0, 0, 0, 4, 0x26, 0x00, (byte) 0x80, 0}, true));
        assertNull("禁止位是 1", readOne(new byte[]{0, 0, 0, 4, (byte) 0xA6, 0x01, (byte) 0x80, 0}, true));
        assertNull("保留类型 24", readOne(new byte[]{0, 0, 0, 4, 0x30, 0x01, (byte) 0x80, 0}, true));
        assertNull("长度超出文件", readOne(new byte[]{0, 0, 0, 9, 0x26, 0x01, (byte) 0x80, 0}, true));

        Mp4Repair.Nal idr = readOne(new byte[]{0, 0, 0, 4, 0x26, 0x01, (byte) 0x80, 0}, true);
        assertTrue(idr.slice);
        assertTrue(idr.key);
        assertTrue("新的一帧的第一个条带能开一帧", idr.opensPicture);
        Mp4Repair.Nal second = readOne(new byte[]{0, 0, 0, 4, 0x02, 0x01, 0x40, 0}, true);
        assertFalse("同一帧的第二个条带开不了一帧", second.opensPicture);
    }

    /** H.264 的 NAL 头：nal_ref_idc 和类型对得上（IDR / 参数集不能是 0，SEI / 分隔符必须是 0）。 */
    @Test
    public void avcNalHeadersMustAgreeWithTheirReferenceIdc() throws IOException {
        assertNotNull("IDR", readOne(new byte[]{0, 0, 0, 2, 0x65, (byte) 0x80}, false));
        assertNotNull("SEI", readOne(new byte[]{0, 0, 0, 2, 0x06, 0x05}, false));
        assertNull("IDR 的 nal_ref_idc 是 0", readOne(new byte[]{0, 0, 0, 2, 0x05, (byte) 0x80}, false));
        assertNull("SEI 的 nal_ref_idc 不是 0", readOne(new byte[]{0, 0, 0, 2, 0x26, 0x05}, false));
        assertNull("类型 0", readOne(new byte[]{0, 0, 0, 2, 0x60, (byte) 0x80}, false));
    }

    private Mp4Repair.Nal readOne(byte[] bytes, boolean hevc) throws IOException {
        File file = folder.newFile();
        Files.write(file.toPath(), bytes);
        try (Mp4Repair.Reader in = new Mp4Repair.Reader(file)) {
            return Mp4Repair.readNal(in, 0, bytes.length, hevc);
        }
    }

    @Test
    public void rollbackPutsTheFileBackExactlyAsItWas() throws IOException {
        File reference = writeReference(folder.newFile("20250101_120000_front.mp4"));
        File broken = writeBroken(folder.newFile("20250101_120100_front.mp4"));
        byte[] original = Files.readAllBytes(broken.toPath());

        Mp4Repair.Repaired repaired =
                Mp4Repair.repair(broken, Mp4Repair.templateFrom(reference), null);
        repaired.rollback();

        assertArrayEquals("退回去之后要和修复前一个字节不差",
                original, Files.readAllBytes(broken.toPath()));
        assertEquals(Mp4Repair.Status.REPAIRABLE, Mp4Repair.scan(broken).status);
    }

    @Test
    public void tellsApartFilesThatAreNotWorthTouching() throws IOException {
        File empty = folder.newFile("empty.mp4");
        assertEquals(Mp4Repair.Status.EMPTY, Mp4Repair.scan(empty).status);

        File justHeaders = folder.newFile("20250101_120200_front.mp4");
        try (FileOutputStream out = new FileOutputStream(justHeaders)) {
            out.write(ftyp());
            out.write(freeBox(256));
            out.write(mdatPlaceholder());
        }
        assertEquals("头写完就断电，一帧都没有", Mp4Repair.Status.EMPTY,
                Mp4Repair.scan(justHeaders).status);

        File notMp4 = folder.newFile("note.txt");
        Files.write(notMp4.toPath(), "这不是视频，只是一段文字而已，长度要够".getBytes("UTF-8"));
        assertEquals(Mp4Repair.Status.UNKNOWN, Mp4Repair.scan(notMp4).status);
    }

    @Test
    public void refusesWhenTheReferenceItselfHasNoIndex() throws IOException {
        File broken = writeBroken(folder.newFile("20250101_120100_front.mp4"));
        try {
            Mp4Repair.templateFrom(broken);
            fail("参考文件自己都没封口，不该当参考");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("没有索引"));
        }
    }

    // ================================================================= 验索引

    private void checkSampleTable(byte[] stbl) {
        byte[] stsz = child(stbl, 8, stbl.length, "stsz");
        byte[] co64 = child(stbl, 8, stbl.length, "co64");
        byte[] stss = child(stbl, 8, stbl.length, "stss");
        byte[] stts = child(stbl, 8, stbl.length, "stts");

        assertEquals(FRAMES, (int) readU32(stsz, 16));
        assertEquals(FRAMES, (int) readU32(co64, 12));
        assertEquals("逐帧列偏移，所以每帧一块", 0, (int) readU32(stsz, 12));

        List<Integer> syncs = new ArrayList<>();
        for (int i = 0; i < FRAMES; i++) {
            long size = readU32(stsz, 20 + i * 4);
            long offset = readU64(co64, 16 + i * 8);
            assertEquals("第 " + (i + 1) + " 帧的位置", expected.get(i)[0], offset);
            assertEquals("第 " + (i + 1) + " 帧的大小", expected.get(i)[1], size);
            if (expected.get(i)[2] == 1) {
                syncs.add(i + 1);
            }
        }

        assertEquals(syncs.size(), (int) readU32(stss, 12));
        for (int i = 0; i < syncs.size(); i++) {
            assertEquals("第 " + (i + 1) + " 个关键帧",
                    (long) syncs.get(i), readU32(stss, 16 + i * 4));
        }

        assertEquals("帧时长一条就够", 1, (int) readU32(stts, 12));
        assertEquals(FRAMES, (int) readU32(stts, 16));
        assertEquals(3100, (int) readU32(stts, 20));
    }

    // ================================================================= 造文件

    /** 完好的参考文件：ftyp + mdat（长度是真的）+ 一个够 templateFrom 读的 moov。 */
    private File writeReference(File file) throws IOException {
        byte[] payload = new byte[]{0, 0, 0, 2, 0x65, (byte) 0x80};
        byte[] mdat = box("mdat", payload);
        byte[] moov = box("moov", box("trak", tkhd(), box("mdia", mdhd(), hdlr(),
                box("minf", box("stbl", stsd(), referenceStts())))));
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(ftyp());
            out.write(mdat);
            out.write(moov);
        }
        return file;
    }

    /**
     * 断电留下的那一份。
     *
     * <p>按 MPEG4Writer 的实际写法：ftyp、一块给 moov 预留的 free、然后 mdat 的盒子头
     * 用「长度 1 + 8 字节 0」占位，后面直接跟画面数据。</p>
     */
    private File writeBroken(File file) throws IOException {
        return writeBroken(file, 0, false);
    }

    /**
     * @param soundEvery 每这么多帧后面插一块声音（几帧 AAC 的样子，没有长度前缀）；0 = 不插
     * @param fakeNal    第一块声音的开头做成像一个 H.264 条带的样子，长度落在后面的画面里（但不正好落在一个 NAL 上）
     */
    private File writeBroken(File file, int soundEvery, boolean fakeNal) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        byte[] header = cat(ftyp(), freeBox(512), mdatPlaceholder());
        long at = header.length;
        long fakeAt = -1;

        for (int i = 0; i < FRAMES; i++) {
            boolean key = i % 10 == 0;
            long start = at;
            long size = 0;
            // 每帧前面挂一个 SEI，和真实编码器的输出一样
            size += write(body, nal(0x06, new byte[]{0x01, 0x02}));
            size += write(body, nal(key ? 0x65 : 0x41,
                    cat(new byte[]{(byte) 0x80}, filler(40 + i))));
            if (i == 5) {
                // 同一帧的第二个条带：first_mb_in_slice 不为 0，不该被当成新的一帧
                size += write(body, nal(0x41, cat(new byte[]{0x40}, filler(20))));
            }
            at += size;
            expected.add(new long[]{start, size, key ? 1 : 0});
            if (soundEvery > 0 && i % soundEvery == soundEvery - 1 && i < FRAMES - 1) {
                byte[] sound = soundBlock(i);
                if (fakeNal && fakeAt < 0) {
                    fakeAt = at;
                    // 长度先空着（造完再定），后面一个字节像 H.264 的非 IDR 条带：nal_ref_idc 2、类型 1
                    sound[4] = 0x41;
                }
                body.write(sound);
                at += sound.length;
                soundBytes += sound.length;
            }
        }

        // 结尾：长度前缀说还有 999999 字节，实际只写了 10 个 —— 断电就断在这里
        body.write(new byte[]{0x00, 0x0F, 0x42, 0x3F});
        body.write(filler(10));

        byte[] data = body.toByteArray();
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(header);
            out.write(data);
        }
        if (fakeAt >= 0) {
            // 假长度：越过这一块声音、落在后面的画面中间，又不正好落在一个认得出的 NAL 上
            // （真碰上那种，从那里接着走也是对的 —— 这里要验的是「落在中间」那种常见的）
            long end = header.length + data.length;
            long length = 3000;
            try (Mp4Repair.Reader in = new Mp4Repair.Reader(file)) {
                while (fakeAt + 4 + length >= end || Mp4Repair.readNal(in, fakeAt + 4 + length, end, false) != null) {
                    length++;
                }
            }
            assertTrue("假长度要越过这一块声音", fakeAt + 4 + length > fakeAt + soundBlock(0).length);
            byte[] patched = u32(length);
            System.arraycopy(patched, 0, data, (int) (fakeAt - header.length), 4);
            try (FileOutputStream out = new FileOutputStream(file)) {
                out.write(header);
                out.write(data);
            }
        }
        return file;
    }

    /**
     * 一块声音：6 帧 AAC 的样子（单声道 AAC 一帧的头一个字节是 0x00 或 0x01，后面是编码数据），每帧 150 字节。
     * 内容按 seed 定，每次造出来一样。
     */
    private static byte[] soundBlock(int seed) {
        byte[] out = new byte[6 * 150];
        long x = 0x9E3779B97F4A7C15L ^ seed;
        for (int i = 0; i < out.length; i++) {
            x = x * 6364136223846793005L + 1442695040888963407L;
            out[i] = (byte) (x >>> 56);
        }
        for (int frame = 0; frame < 6; frame++) {
            out[frame * 150] = (byte) (frame % 2);
        }
        return out;
    }

    private static int write(ByteArrayOutputStream out, byte[] bytes) throws IOException {
        out.write(bytes);
        return bytes.length;
    }

    /** 一个带 4 字节长度前缀的 NAL。 */
    private static byte[] nal(int headerByte, byte[] payload) {
        byte[] body = cat(new byte[]{(byte) headerByte}, payload);
        return cat(u32(body.length), body);
    }

    private static byte[] filler(int length) {
        byte[] out = new byte[length];
        for (int i = 0; i < length; i++) {
            out[i] = (byte) (0xA0 + (i % 16));
        }
        return out;
    }

    private static byte[] ftyp() {
        return box("ftyp", type("isom"), u32(512), type("isom"), type("mp42"));
    }

    private static byte[] freeBox(int contentBytes) {
        return box("free", new byte[contentBytes]);
    }

    /** MPEG4Writer 的占位写法：长度写 1，后面 8 字节的真长度留成 0，停止时才补。 */
    private static byte[] mdatPlaceholder() {
        return cat(u32(1), type("mdat"), new byte[8]);
    }

    private static byte[] tkhd() {
        return fullBox("tkhd", 0, 7, cat(
                u32(0), u32(0), u32(1), u32(0), u32(1000),
                new byte[8], u16(0), u16(0), u16(0), u16(0),
                new byte[36], u32(1920L << 16), u32(1080L << 16)));
    }

    private static byte[] mdhd() {
        return fullBox("mdhd", 0, 0, cat(
                u32(0), u32(0), u32(TIMESCALE), u32(93000), u16(0x55C4), u16(0)));
    }

    private static byte[] hdlr() {
        return fullBox("hdlr", 0, 0, cat(u32(0), type("vide"), new byte[12], new byte[]{0}));
    }

    /** 29 帧 3000 刻度 + 1 帧 6000 刻度：平均 3100，专门用来验平均是按帧数加权的。 */
    private static byte[] referenceStts() {
        return fullBox("stts", 0, 0, cat(u32(2), u32(29), u32(3000), u32(1), u32(6000)));
    }

    private static byte[] stsd() {
        byte[] avcC = box("avcC", new byte[]{1, 0x64, 0, 0x1F, (byte) 0xFF, (byte) 0xE1,
                0, 4, 0x27, 0x64, 0, 0x1F, 1, 0, 4, 0x28, (byte) 0xEE, 0x3C, (byte) 0xB0});
        byte[] avc1 = box("avc1", cat(
                new byte[6], u16(1), new byte[16], u16(1920), u16(1080),
                u32(0x00480000L), u32(0x00480000L), u32(0), u16(1), new byte[32],
                u16(0x0018), u16(0xFFFF)), avcC);
        return fullBox("stsd", 0, 0, cat(u32(1), avc1));
    }

    // ================================================================= 读盒子

    /** 在 [start, end) 里找一个盒子，连头带内容整块返回。 */
    private static byte[] child(byte[] data, int start, int end, String name) {
        int at = start;
        while (at + 8 <= end) {
            long size = readU32(data, at);
            if (size == 1) {
                size = readU64(data, at + 8);
            }
            if (size < 8 || at + size > end) {
                break;
            }
            if (name.equals(readType(data, at + 4))) {
                byte[] out = new byte[(int) size];
                System.arraycopy(data, at, out, 0, out.length);
                return out;
            }
            at += (int) size;
        }
        throw new AssertionError("找不到 " + name);
    }

    /** 一层层往下找，返回最里面那个盒子。 */
    private static byte[] path(byte[] parent, String... names) {
        byte[] current = parent;
        for (String name : names) {
            current = child(current, 8, current.length, name);
        }
        return current;
    }

    /** 顶层盒子的 {起点, 长度}。 */
    private static long[] boxAt(byte[] data, String name) {
        int at = 0;
        while (at + 8 <= data.length) {
            long size = readU32(data, at);
            int header = 8;
            if (size == 1) {
                size = readU64(data, at + 8);
                header = 16;
            }
            if (name.equals(readType(data, at + 4))) {
                return new long[]{at, size};
            }
            if (size < header || at + size > data.length) {
                break;
            }
            at += (int) size;
        }
        throw new AssertionError("找不到顶层的 " + name);
    }
}
