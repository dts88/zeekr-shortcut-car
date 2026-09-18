package com.kooo.evcam.repair;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;

/**
 * 给断电时没来得及封口的 MP4 重建索引。
 *
 * <h3>断电时文件里到底还剩什么</h3>
 *
 * <p>MediaMuxer（以及 MediaRecorder，底层是同一个 MPEG4Writer）写 MP4 的顺序是：
 * 开头写 {@code ftyp}，再留一块 {@code free} 给将来的索引，然后写 {@code mdat} 的盒子头
 * —— 头里的长度是个占位符（{@code size=1} 加 8 字节的 0），因为这时候还不知道会录多长。
 * 之后每编码出一帧就往后追加，一直到 {@code stop()} 那一刻才回头把 {@code mdat} 的长度补上、
 * 把 {@code moov} 写进去。</p>
 *
 * <p>所以断电留下的文件是：画面数据一帧不少地躺在盘上，但<b>没有目录</b> ——
 * 每帧在哪、多长、什么时候放、解码器该怎么初始化，全都在那个没写成的 {@code moov} 里。
 * 播放器打不开它，不是因为画面坏了，是因为不知道从哪读起。</p>
 *
 * <h3>怎么补回来</h3>
 *
 * <p>{@code mdat} 里是一串<b>带 4 字节长度前缀的 NAL</b>（MPEG4Writer 把编码器给的起始码
 * 换成了长度）。顺着长度前缀就能一个接一个走完整个文件，走出每一帧的位置和大小 ——
 * 这一步不需要解码，只读每个 NAL 的头一个字节判断类型。</p>
 *
 * <p>唯一补不出来的是解码参数 SPS/PPS：本应用把编码器的 codec-config 交给了 MediaMuxer
 * 写进 {@code moov}，没有混在画面数据里（见 CodecVideoRecorder 对
 * {@code BUFFER_FLAG_CODEC_CONFIG} 的处理）。所以必须找一个<b>同一路相机、同样参数</b>
 * 录出来的完好文件当参考，把它的 {@code stsd} 整块抄过来 —— 这也是 untrunc 这类工具
 * 都要求提供参考文件的原因。行车记录仪这个场景反而最友好：参考文件遍地都是，
 * 参数和坏文件完全一致。</p>
 *
 * <h3>就地改，不拷贝</h3>
 *
 * <p>修复时不生成新文件：在原文件尾部追加一个 {@code moov}，再把 {@code mdat} 的长度补上。
 * 一是 U 盘可能本来就快满了，拷一份等于要两倍空间；二是文件名就是分段的分组键
 * （{@code 20250101_120000_front.mp4}），改名之后回看界面就不认它了。</p>
 *
 * <p>写入顺序是<b>先追加 moov，再补 mdat 长度</b>。万一补长度之前又断电，文件和修复前
 * 完全一样，不会变得更坏。真出了问题还能 {@link Repaired#rollback()} 退回去。</p>
 */
public final class Mp4Repair {

    /** 单个 NAL 的长度上限，超过就认为已经不是有效数据了（32MB，远大于任何一帧）。 */
    private static final long MAX_NAL_BYTES = 32L * 1024 * 1024;

    /** moov 读进内存的上限。正常的 moov 只有几十到几百 KB。 */
    private static final int MAX_MOOV_BYTES = 64 * 1024 * 1024;

    private Mp4Repair() {
    }

    // ================================================================= 探查

    public enum Status {
        /** 有 moov，正常文件。 */
        HEALTHY,
        /** 有画面数据但没有 moov —— 可以修。 */
        REPAIRABLE,
        /** 连一帧都没写进去，修了也是空的。 */
        EMPTY,
        /** 不像本应用录的 MP4。 */
        UNKNOWN
    }

    /** 探查结果：这个文件是什么状态，画面数据在哪一段。 */
    public static final class Scan {
        public final File file;
        public final Status status;
        /** mdat 盒子头的位置。 */
        public final long mdatBoxStart;
        /** 样本数据的起点（盒子头之后）。 */
        public final long dataStart;
        /** 样本数据的终点。 */
        public final long dataEnd;
        /** 盒子头是不是 16 字节的大长度形式。 */
        public final boolean largeHeader;

        Scan(File file, Status status, long mdatBoxStart, long dataStart, long dataEnd,
             boolean largeHeader) {
            this.file = file;
            this.status = status;
            this.mdatBoxStart = mdatBoxStart;
            this.dataStart = dataStart;
            this.dataEnd = dataEnd;
            this.largeHeader = largeHeader;
        }

        public long dataBytes() {
            return Math.max(0, dataEnd - dataStart);
        }
    }

    /**
     * 只读文件头，判断状态。
     *
     * <p>顺着顶层盒子往后跳，所以完好的文件也只读几十个字节 —— U 盘上扫一整个目录很快。</p>
     */
    public static Scan scan(File file) {
        if (file == null || !file.isFile()) {
            return new Scan(file, Status.UNKNOWN, -1, -1, -1, false);
        }
        long length = file.length();
        if (length < 16) {
            return new Scan(file, Status.EMPTY, -1, -1, -1, false);
        }
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            long pos = 0;
            long mdatBox = -1;
            long dataStart = -1;
            long dataEnd = -1;
            boolean large = false;
            byte[] head = new byte[16];
            while (pos + 8 <= length) {
                raf.seek(pos);
                raf.readFully(head, 0, 8);
                long size = readU32(head, 0);
                String type = readType(head, 4);
                int headerSize = 8;
                if (size == 1) {
                    if (pos + 16 > length) {
                        break;
                    }
                    raf.readFully(head, 8, 8);
                    size = readU64(head, 8);
                    headerSize = 16;
                }
                if ("moov".equals(type)) {
                    return new Scan(file, Status.HEALTHY, mdatBox, dataStart, dataEnd, large);
                }
                if ("mdat".equals(type)) {
                    mdatBox = pos;
                    dataStart = pos + headerSize;
                    large = headerSize == 16;
                    // 长度没补上（占位的 0）或者超出文件尾（写到一半断电）→ 数据一直到文件尾
                    boolean bogus = size <= headerSize || pos + size > length;
                    dataEnd = bogus ? length : pos + size;
                    if (bogus) {
                        // 长度是假的，没法靠它跳到下一个盒子，后面也不会再有东西了
                        break;
                    }
                }
                if (size <= 0) {
                    break;
                }
                pos += size;
            }
            if (dataStart < 0) {
                return new Scan(file, Status.UNKNOWN, -1, -1, -1, false);
            }
            if (dataEnd - dataStart < 8) {
                return new Scan(file, Status.EMPTY, mdatBox, dataStart, dataEnd, large);
            }
            return new Scan(file, Status.REPAIRABLE, mdatBox, dataStart, dataEnd, large);
        } catch (IOException e) {
            return new Scan(file, Status.UNKNOWN, -1, -1, -1, false);
        }
    }

    // ================================================================= 参考文件

    /** 从一个完好的文件里抄来的那些补不出来的东西。 */
    public static final class Template {
        public final File source;
        /** 整块 stsd（含 avc1/hvc1 和里面的 SPS/PPS），原样搬过去。 */
        final byte[] stsd;
        /** mdia 的时间刻度。 */
        public final long timescale;
        /** 平均每帧多少刻度 —— 参考文件的实测帧率，比标称值更接近真相。 */
        public final long sampleTicks;
        public final int width;
        public final int height;
        public final boolean hevc;

        Template(File source, byte[] stsd, long timescale, long sampleTicks,
                 int width, int height, boolean hevc) {
            this.source = source;
            this.stsd = stsd;
            this.timescale = timescale;
            this.sampleTicks = sampleTicks;
            this.width = width;
            this.height = height;
            this.hevc = hevc;
        }

        /** 参考文件折算出来的帧率，只用来显示。 */
        public float fps() {
            return sampleTicks > 0 ? (float) timescale / sampleTicks : 0f;
        }
    }

    /**
     * 读一个完好文件的视频轨，取出重建索引要用的东西。
     *
     * @throws IOException 文件不完整、没有视频轨、或者结构不认识
     */
    public static Template templateFrom(File healthy) throws IOException {
        byte[] moov = readMoov(healthy);
        int[] moovRange = {8, moov.length};
        int[] trak = null;
        int[] mdia = null;
        for (int[] candidate : findAll(moov, moovRange[0], moovRange[1], "trak")) {
            int[] m = find(moov, candidate[0], candidate[1], "mdia");
            if (m == null) {
                continue;
            }
            int[] hdlr = find(moov, m[0], m[1], "hdlr");
            // hdlr 内容：4 版本/标志 + 4 预留 + 4 handler_type
            if (hdlr != null && hdlr[1] - hdlr[0] >= 12
                    && "vide".equals(readType(moov, hdlr[0] + 8))) {
                trak = candidate;
                mdia = m;
                break;
            }
        }
        if (trak == null) {
            throw new IOException("参考文件里没有视频轨");
        }

        int[] mdhd = find(moov, mdia[0], mdia[1], "mdhd");
        if (mdhd == null) {
            throw new IOException("参考文件缺 mdhd");
        }
        int version = moov[mdhd[0]] & 0xFF;
        long timescale = version == 1
                ? readU32(moov, mdhd[0] + 20) : readU32(moov, mdhd[0] + 12);
        if (timescale <= 0) {
            throw new IOException("参考文件的时间刻度不合法");
        }

        int[] minf = find(moov, mdia[0], mdia[1], "minf");
        int[] stbl = minf == null ? null : find(moov, minf[0], minf[1], "stbl");
        int[] stsdRange = stbl == null ? null : find(moov, stbl[0], stbl[1], "stsd");
        if (stsdRange == null) {
            throw new IOException("参考文件缺 stsd（解码参数）");
        }
        byte[] stsd = new byte[(stsdRange[1] - stsdRange[0]) + 8];
        System.arraycopy(moov, stsdRange[0] - 8, stsd, 0, stsd.length);
        // stsd 内容：4 版本/标志 + 4 条目数，然后第一个样本条目（4 长度 + 4 类型）
        String codec = stsd.length >= 24 ? readType(stsd, 20) : "";
        boolean hevc = codec.startsWith("hvc") || codec.startsWith("hev");

        int[] stts = find(moov, stbl[0], stbl[1], "stts");
        long ticks = averageSampleTicks(moov, stts);
        if (ticks <= 0) {
            throw new IOException("参考文件算不出帧时长");
        }

        int[] tkhd = find(moov, trak[0], trak[1], "tkhd");
        int width = 0;
        int height = 0;
        if (tkhd != null) {
            int base = (moov[tkhd[0]] & 0xFF) == 1 ? tkhd[0] + 88 : tkhd[0] + 76;
            if (base + 8 <= tkhd[1]) {
                width = (int) (readU32(moov, base) >> 16);
                height = (int) (readU32(moov, base + 4) >> 16);
            }
        }
        return new Template(healthy, stsd, timescale, ticks, width, height, hevc);
    }

    /** stts 是 (帧数, 每帧刻度) 的表，全部平均 —— 帧率不稳的时候这个比取第一条准。 */
    private static long averageSampleTicks(byte[] moov, int[] stts) {
        if (stts == null || stts[1] - stts[0] < 8) {
            return 0;
        }
        long entries = readU32(moov, stts[0] + 4);
        long samples = 0;
        long total = 0;
        for (long i = 0; i < entries; i++) {
            int at = (int) (stts[0] + 8 + i * 8);
            if (at + 8 > stts[1]) {
                break;
            }
            long count = readU32(moov, at);
            long delta = readU32(moov, at + 4);
            samples += count;
            total += count * delta;
        }
        return samples > 0 ? Math.max(1, total / samples) : 0;
    }

    private static byte[] readMoov(File file) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            long length = raf.length();
            long pos = 0;
            byte[] head = new byte[16];
            while (pos + 8 <= length) {
                raf.seek(pos);
                raf.readFully(head, 0, 8);
                long size = readU32(head, 0);
                String type = readType(head, 4);
                if (size == 1) {
                    if (pos + 16 > length) {
                        break;
                    }
                    raf.readFully(head, 8, 8);
                    size = readU64(head, 8);
                }
                if ("moov".equals(type)) {
                    if (size < 8 || size > MAX_MOOV_BYTES || pos + size > length) {
                        throw new IOException("参考文件的索引不完整");
                    }
                    byte[] moov = new byte[(int) size];
                    raf.seek(pos);
                    raf.readFully(moov);
                    return moov;
                }
                if (size <= 0 || pos + size > length) {
                    break;
                }
                pos += size;
            }
        }
        throw new IOException("参考文件里没有索引（它自己也没封口）");
    }

    // ================================================================= 修复

    /** 扫描进度，给界面用。 */
    public interface Progress {
        void onProgress(long done, long total);
    }

    /** 修复结果。留着回退需要的东西，验证不过就退回去。 */
    public static final class Repaired {
        public final File file;
        public final int samples;
        public final int syncSamples;
        public final long durationMs;
        /** 结尾没能认出来的字节数 —— 断电时写了一半的那一帧。 */
        public final long tailIgnored;
        private final long originalLength;
        private final long headerPos;
        private final byte[] headerBytes;

        Repaired(File file, int samples, int syncSamples, long durationMs, long tailIgnored,
                 long originalLength, long headerPos, byte[] headerBytes) {
            this.file = file;
            this.samples = samples;
            this.syncSamples = syncSamples;
            this.durationMs = durationMs;
            this.tailIgnored = tailIgnored;
            this.originalLength = originalLength;
            this.headerPos = headerPos;
            this.headerBytes = headerBytes;
        }

        /** 退回修复前：砍掉追加的 moov，把 mdat 的长度写回占位符。 */
        public void rollback() throws IOException {
            try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
                raf.setLength(originalLength);
                raf.seek(headerPos);
                raf.write(headerBytes);
            }
        }
    }

    /**
     * 补一个索引进去。
     *
     * @param broken   探查结果为 {@link Status#REPAIRABLE} 的文件
     * @param template 同一路相机的完好文件给出的解码参数和帧时长
     */
    public static Repaired repair(File broken, Template template, Progress progress)
            throws IOException {
        Scan scan = scan(broken);
        if (scan.status != Status.REPAIRABLE) {
            throw new IOException("这个文件不需要修，或者修不了：" + scan.status);
        }
        Samples samples = walk(broken, scan, template.hevc, progress);
        if (samples.count == 0) {
            throw new IOException("一帧都没认出来，可能不是本应用录的");
        }

        long originalLength = broken.length();
        byte[] header = new byte[scan.largeHeader ? 16 : 8];
        try (RandomAccessFile raf = new RandomAccessFile(broken, "r")) {
            raf.seek(scan.mdatBoxStart);
            raf.readFully(header);
        }

        long mdatBytes = originalLength - scan.mdatBoxStart;
        if (!scan.largeHeader && mdatBytes > 0xFFFFFFFFL) {
            throw new IOException("文件超过 4GB，但盒子头是 32 位的，改不了");
        }

        byte[] moov = buildMoov(template, samples);
        try (RandomAccessFile raf = new RandomAccessFile(broken, "rw")) {
            // 先追加索引：这一步半途而废，文件和原来一样，还是那个没封口的文件
            raf.seek(originalLength);
            raf.write(moov);
            // 再补 mdat 的长度，让它正好停在索引前面
            raf.seek(scan.mdatBoxStart);
            if (scan.largeHeader) {
                byte[] patch = new byte[8];
                writeU64(patch, 0, mdatBytes);
                raf.seek(scan.mdatBoxStart + 8);
                raf.write(patch);
            } else {
                byte[] patch = new byte[4];
                writeU32(patch, 0, mdatBytes);
                raf.write(patch);
            }
            raf.getFD().sync();
        }

        long durationMs = samples.count * template.sampleTicks * 1000L / template.timescale;
        return new Repaired(broken, samples.count, samples.syncCount, durationMs,
                scan.dataEnd - samples.consumedTo, originalLength, scan.mdatBoxStart, header);
    }

    // ----------------------------------------------------------------- 走一遍 NAL

    /** 走出来的样本表。 */
    static final class Samples {
        long[] offsets = new long[1024];
        int[] sizes = new int[1024];
        int count;
        int[] syncs = new int[256];
        int syncCount;
        /** 认到哪个字节为止。 */
        long consumedTo;

        void add(long offset, long size, boolean sync) {
            if (count == offsets.length) {
                long[] o = new long[count * 2];
                int[] s = new int[count * 2];
                System.arraycopy(offsets, 0, o, 0, count);
                System.arraycopy(sizes, 0, s, 0, count);
                offsets = o;
                sizes = s;
            }
            offsets[count] = offset;
            sizes[count] = (int) size;
            count++;
            if (sync) {
                if (syncCount == syncs.length) {
                    int[] t = new int[syncCount * 2];
                    System.arraycopy(syncs, 0, t, 0, syncCount);
                    syncs = t;
                }
                syncs[syncCount++] = count;  // 样本号从 1 数起
            }
        }
    }

    /**
     * 顺着长度前缀走完 mdat，把 NAL 归拢成一帧一帧。
     *
     * <h3>怎么算一帧</h3>
     *
     * <p>一帧（接入单元）可能由几个 NAL 组成：SEI、参数集、然后是一个或多个条带。
     * 判断新一帧开始的规则是：已经见过条带之后，再来一个非条带的 NAL，或者再来一个
     * <b>标着「从第 0 个宏块开始」</b>的条带。</p>
     *
     * <p>那个标志位好取：H.264 条带头的第一个字段是 {@code first_mb_in_slice}，
     * 指数哥伦布编码，值为 0 时第一个比特就是 1；HEVC 条带头的第一个比特正好是
     * {@code first_slice_segment_in_pic_flag}。两者都是「首字节最高位为 1 即新一帧」，
     * 不用真的去解析条带头。</p>
     */
    private static Samples walk(File file, Scan scan, boolean hevc, Progress progress)
            throws IOException {
        Samples out = new Samples();
        long total = scan.dataBytes();
        long pos = scan.dataStart;
        long sampleStart = -1;
        long sampleSize = 0;
        boolean sampleSync = false;
        boolean haveSlice = false;
        long lastReport = 0;

        try (InputStream raw = new FileInputStream(file)) {
            skipFully(raw, scan.dataStart);
            BufferedInputStream in = new BufferedInputStream(raw, 1 << 16);
            byte[] head = new byte[8];
            while (pos + 4 <= scan.dataEnd) {
                if (!readFully(in, head, 0, 4)) {
                    break;
                }
                long nalLength = readU32(head, 0);
                if (nalLength <= 0 || nalLength > MAX_NAL_BYTES
                        || pos + 4 + nalLength > scan.dataEnd) {
                    break;  // 结尾那一帧写了一半，或者已经不是有效数据
                }
                int peek = (int) Math.min(3, nalLength);
                if (!readFully(in, head, 4, peek)) {
                    break;
                }
                int first = head[4] & 0xFF;
                int type = hevc ? (first >> 1) & 0x3F : first & 0x1F;
                boolean slice = hevc ? type <= 31 : (type >= 1 && type <= 5);
                boolean key = hevc ? (type >= 16 && type <= 21) : type == 5;
                // 条带头的首字节：H.264 在 NAL 头之后 1 字节，HEVC 之后 2 字节
                int headerBytes = hevc ? 2 : 1;
                boolean newPicture = slice && peek >= headerBytes + 1
                        && (head[4 + headerBytes] & 0x80) != 0;

                boolean starts = sampleStart < 0 || (haveSlice && (!slice || newPicture));
                if (starts) {
                    if (sampleStart >= 0) {
                        out.add(sampleStart, sampleSize, sampleSync);
                    }
                    sampleStart = pos;
                    sampleSize = 0;
                    sampleSync = false;
                    haveSlice = false;
                }
                sampleSize += 4 + nalLength;
                if (slice) {
                    haveSlice = true;
                    if (key) {
                        sampleSync = true;
                    }
                }

                skipFully(in, nalLength - peek);
                pos += 4 + nalLength;
                if (progress != null && pos - lastReport > (4 << 20)) {
                    lastReport = pos;
                    progress.onProgress(pos - scan.dataStart, total);
                }
            }
        }
        if (sampleStart >= 0) {
            out.add(sampleStart, sampleSize, sampleSync);
        }
        out.consumedTo = pos;
        if (progress != null) {
            progress.onProgress(total, total);
        }
        return out;
    }

    // ----------------------------------------------------------------- 拼索引

    private static byte[] buildMoov(Template t, Samples s) {
        long mediaDuration = (long) s.count * t.sampleTicks;
        long movieDuration = mediaDuration * 1000L / t.timescale;

        byte[] mvhd = fullBox("mvhd", 0, 0, cat(
                u32(0), u32(0), u32(1000), u32(movieDuration),
                u32(0x00010000L), u16(0x0100), u16(0), u32(0), u32(0),
                matrix(), new byte[24], u32(2)));

        byte[] tkhd = fullBox("tkhd", 0, 0x7, cat(
                u32(0), u32(0), u32(1), u32(0), u32(movieDuration),
                new byte[8], u16(0), u16(0), u16(0), u16(0),
                matrix(), u32((long) t.width << 16), u32((long) t.height << 16)));

        byte[] mdhd = fullBox("mdhd", 0, 0, cat(
                u32(0), u32(0), u32(t.timescale), u32(mediaDuration),
                u16(0x55C4), u16(0)));

        byte[] hdlr = fullBox("hdlr", 0, 0, cat(
                u32(0), type("vide"), new byte[12], new byte[]{'v', 'i', 'd', 'e', 0}));

        byte[] vmhd = fullBox("vmhd", 0, 1, new byte[8]);
        byte[] dref = fullBox("dref", 0, 0, cat(u32(1), fullBox("url ", 0, 1, new byte[0])));
        byte[] dinf = box("dinf", dref);

        byte[] stts = fullBox("stts", 0, 0, cat(u32(1), u32(s.count), u32(t.sampleTicks)));

        byte[] syncList = new byte[4 * s.syncCount];
        for (int i = 0; i < s.syncCount; i++) {
            writeU32(syncList, i * 4, s.syncs[i]);
        }
        byte[] stss = fullBox("stss", 0, 0, cat(u32(s.syncCount), syncList));

        // 一帧一块，最笨也最不会错：stsc 只要一条，偏移表逐帧列出来
        byte[] stsc = fullBox("stsc", 0, 0, cat(u32(1), u32(1), u32(1), u32(1)));

        byte[] sizeList = new byte[4 * s.count];
        for (int i = 0; i < s.count; i++) {
            writeU32(sizeList, i * 4, s.sizes[i]);
        }
        byte[] stsz = fullBox("stsz", 0, 0, cat(u32(0), u32(s.count), sizeList));

        // 一律用 64 位偏移：10 分钟的大分段有可能超过 4GB，多出来的几十 KB 无所谓
        byte[] offsetList = new byte[8 * s.count];
        for (int i = 0; i < s.count; i++) {
            writeU64(offsetList, i * 8, s.offsets[i]);
        }
        byte[] co64 = fullBox("co64", 0, 0, cat(u32(s.count), offsetList));

        byte[] stbl = box("stbl", t.stsd, stts, stss, stsc, stsz, co64);
        byte[] minf = box("minf", vmhd, dinf, stbl);
        byte[] mdia = box("mdia", mdhd, hdlr, minf);
        byte[] trak = box("trak", tkhd, mdia);
        return box("moov", mvhd, trak);
    }

    // ================================================================= 字节工具

    static byte[] box(String type, byte[]... parts) {
        int length = 8;
        for (byte[] part : parts) {
            length += part.length;
        }
        byte[] out = new byte[length];
        writeU32(out, 0, length);
        System.arraycopy(type(type), 0, out, 4, 4);
        int at = 8;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, out, at, part.length);
            at += part.length;
        }
        return out;
    }

    static byte[] fullBox(String type, int version, int flags, byte[]... parts) {
        byte[] head = new byte[4];
        head[0] = (byte) version;
        head[1] = (byte) (flags >> 16);
        head[2] = (byte) (flags >> 8);
        head[3] = (byte) flags;
        byte[][] all = new byte[parts.length + 1][];
        all[0] = head;
        System.arraycopy(parts, 0, all, 1, parts.length);
        return box(type, all);
    }

    private static byte[] matrix() {
        return cat(u32(0x00010000L), u32(0), u32(0),
                u32(0), u32(0x00010000L), u32(0),
                u32(0), u32(0), u32(0x40000000L));
    }

    static byte[] cat(byte[]... parts) {
        int length = 0;
        for (byte[] part : parts) {
            length += part.length;
        }
        byte[] out = new byte[length];
        int at = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, out, at, part.length);
            at += part.length;
        }
        return out;
    }

    static byte[] u16(int value) {
        return new byte[]{(byte) (value >> 8), (byte) value};
    }

    static byte[] u32(long value) {
        byte[] out = new byte[4];
        writeU32(out, 0, value);
        return out;
    }

    static byte[] type(String fourcc) {
        byte[] out = new byte[4];
        for (int i = 0; i < 4; i++) {
            out[i] = (byte) fourcc.charAt(i);
        }
        return out;
    }

    static void writeU32(byte[] out, int at, long value) {
        out[at] = (byte) (value >> 24);
        out[at + 1] = (byte) (value >> 16);
        out[at + 2] = (byte) (value >> 8);
        out[at + 3] = (byte) value;
    }

    static void writeU64(byte[] out, int at, long value) {
        for (int i = 0; i < 8; i++) {
            out[at + i] = (byte) (value >> (56 - 8 * i));
        }
    }

    static long readU32(byte[] in, int at) {
        return ((long) (in[at] & 0xFF) << 24) | ((in[at + 1] & 0xFF) << 16)
                | ((in[at + 2] & 0xFF) << 8) | (in[at + 3] & 0xFF);
    }

    static long readU64(byte[] in, int at) {
        long value = 0;
        for (int i = 0; i < 8; i++) {
            value = (value << 8) | (in[at + i] & 0xFF);
        }
        return value;
    }

    static String readType(byte[] in, int at) {
        return new String(new char[]{
                (char) (in[at] & 0xFF), (char) (in[at + 1] & 0xFF),
                (char) (in[at + 2] & 0xFF), (char) (in[at + 3] & 0xFF)});
    }

    /** 在 [start, end) 范围里找一个子盒子，返回它的内容区间 {内容起点, 内容终点}。 */
    private static int[] find(byte[] data, int start, int end, String type) {
        List<int[]> all = findAll(data, start, end, type);
        return all.isEmpty() ? null : all.get(0);
    }

    private static List<int[]> findAll(byte[] data, int start, int end, String type) {
        List<int[]> found = new ArrayList<>();
        int at = start;
        while (at + 8 <= end) {
            long size = readU32(data, at);
            String kind = readType(data, at + 4);
            int headerSize = 8;
            if (size == 1) {
                if (at + 16 > end) {
                    break;
                }
                size = readU64(data, at + 8);
                headerSize = 16;
            }
            if (size < headerSize || at + size > end) {
                break;
            }
            if (type.equals(kind)) {
                found.add(new int[]{at + headerSize, (int) (at + size)});
            }
            at += (int) size;
        }
        return found;
    }

    private static boolean readFully(InputStream in, byte[] out, int at, int length)
            throws IOException {
        int done = 0;
        while (done < length) {
            int read = in.read(out, at + done, length - done);
            if (read < 0) {
                return false;
            }
            done += read;
        }
        return true;
    }

    private static void skipFully(InputStream in, long bytes) throws IOException {
        long left = bytes;
        while (left > 0) {
            long skipped = in.skip(left);
            if (skipped <= 0) {
                if (in.read() < 0) {
                    return;
                }
                skipped = 1;
            }
            left -= skipped;
        }
    }
}
