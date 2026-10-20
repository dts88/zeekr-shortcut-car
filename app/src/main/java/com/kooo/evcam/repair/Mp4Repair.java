package com.kooo.evcam.repair;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
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
 * <p>录着声音的文件（环视那一路），{@code mdat} 里是画面一块、声音一块交替着（MPEG4Writer 按时间把每条轨攒成块写）。
 * 声音没有长度前缀，顺着画面走到一块声音就接不上了：从那里往后找下一段画面 —— 一个能开一帧的 NAL，
 * 后面接得上再两个（{@link #opensRun}）—— 从那里接着走。声音那几块跳过，修好的文件只有画面。</p>
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
        /** 夹在画面中间、跳过的字节数 —— 声音那几块（录着声音的文件），修好的文件里只有画面。 */
        public final long otherBytes;
        private final long originalLength;
        private final long headerPos;
        private final byte[] headerBytes;

        Repaired(File file, int samples, int syncSamples, long durationMs, long tailIgnored, long otherBytes,
                 long originalLength, long headerPos, byte[] headerBytes) {
            this.file = file;
            this.samples = samples;
            this.syncSamples = syncSamples;
            this.durationMs = durationMs;
            this.tailIgnored = tailIgnored;
            this.otherBytes = otherBytes;
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
                scan.dataEnd - samples.consumedTo, samples.otherBytes, originalLength, scan.mdatBoxStart, header);
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
        /** 中间跳过的字节（声音那几块）。 */
        long otherBytes;

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
     *
     * <h3>接不上的时候</h3>
     *
     * <p>一个 NAL 认不出来（{@link #readNal}），就是走到了别的东西：一块声音，或者结尾写了一半的那一帧。
     * 往后找下一段画面（{@link #resync}），找到了从那里接着走，前面那一帧到此为止；找不到就是结尾。</p>
     *
     * <p>还有一种：一块声音开头那几个字节碰巧像一个 NAL，长度又落在文件里 —— 照它走会一口跳过后面好几帧画面。
     * 所以一个 NAL 后面接不上时（它可能是一段画面的最后一个，也可能就是这种假的），先在它自己这一截里找：
     * 找到一段画面，说明它是假的，从那里接着走；找不到才算它是这一段的最后一个。真的那种这一截里全是编码数据，
     * 找不出能连着接上三个 NAL 的位置。</p>
     */
    private static Samples walk(File file, Scan scan, boolean hevc, Progress progress)
            throws IOException {
        Samples out = new Samples();
        long total = scan.dataBytes();
        long end = scan.dataEnd;
        long pos = scan.dataStart;
        long sampleStart = -1;
        long sampleSize = 0;
        boolean sampleSync = false;
        boolean haveSlice = false;
        long lastReport = 0;

        try (Reader in = new Reader(file)) {
            while (pos + 4 <= end) {
                Nal nal = readNal(in, pos, end, hevc);
                // 不是画面时从哪里接着走；-1 = 后面再没有画面了
                long next = -1;
                if (nal != null) {
                    long after = pos + 4 + nal.length;
                    if (after < end && readNal(in, after, end, hevc) == null) {
                        long inner = resync(in, pos + 1, after, end, hevc);
                        if (inner >= 0) {
                            nal = null;  // 它自己就是声音里像 NAL 的几个字节
                            next = inner;
                        }
                    }
                } else {
                    next = resync(in, pos + 1, end, end, hevc);
                }
                if (nal == null) {
                    if (next < 0) {
                        break;  // 结尾那一帧写了一半，或者后面已经不是有效数据
                    }
                    // 画面在这里断开过：前面那一帧到此为止
                    if (sampleStart >= 0) {
                        out.add(sampleStart, sampleSize, sampleSync);
                        sampleStart = -1;
                    }
                    out.otherBytes += next - pos;
                    pos = next;
                    continue;
                }

                boolean starts = sampleStart < 0 || (haveSlice && (!nal.slice || nal.newPicture));
                if (starts) {
                    if (sampleStart >= 0) {
                        out.add(sampleStart, sampleSize, sampleSync);
                    }
                    sampleStart = pos;
                    sampleSize = 0;
                    sampleSync = false;
                    haveSlice = false;
                }
                sampleSize += 4 + nal.length;
                if (nal.slice) {
                    haveSlice = true;
                    if (nal.key) {
                        sampleSync = true;
                    }
                }

                pos += 4 + nal.length;
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

    /** 一个 NAL 的长度和类型（{@link #readNal}）。 */
    static final class Nal {
        final long length;
        final boolean slice;
        final boolean key;
        /** 条带，而且标着从第 0 个宏块开始（新的一帧）。 */
        final boolean newPicture;
        /** 能开一帧：参数集、SEI、访问单元分隔符，或者新的一帧的第一个条带。一段画面的第一个 NAL 一定是它。 */
        final boolean opensPicture;

        Nal(long length, boolean slice, boolean key, boolean newPicture, boolean opensPicture) {
            this.length = length;
            this.slice = slice;
            this.key = key;
            this.newPicture = newPicture;
            this.opensPicture = opensPicture;
        }
    }

    /** 往后找一段画面时，要连着接上几个 NAL 才算数（正好走到头的除外）。 */
    private static final int RUN_NALS = 3;

    /**
     * pos 这里是不是一个像样的 NAL：长度在文件里、不超过上限；NAL 头合法 —— 禁止位是 0，类型是编码器会出的那几种，
     * HEVC 的层号是 0、时域号不是 0，H.264 的 nal_ref_idc 和类型对得上（标准里规定死的那几条）。
     * 声音、写了一半的结尾、编码数据中间随便一个位置，几乎都过不了这几条。
     *
     * @return 认不出是 null
     */
    static Nal readNal(Reader in, long pos, long end, boolean hevc) throws IOException {
        if (pos + 4 > end) {
            return null;
        }
        byte[] head = in.head;
        if (!in.read(pos, head, 0, 4)) {
            return null;
        }
        long length = readU32(head, 0);
        int headerBytes = hevc ? 2 : 1;
        if (length < headerBytes || length > MAX_NAL_BYTES || pos + 4 + length > end) {
            return null;
        }
        int peek = (int) Math.min(3, length);
        if (!in.read(pos + 4, head, 4, peek)) {
            return null;
        }
        int b0 = head[4] & 0xFF;
        if ((b0 & 0x80) != 0) {
            return null;  // forbidden_zero_bit
        }
        int type;
        boolean slice;
        boolean key;
        boolean opener;
        if (hevc) {
            int b1 = head[5] & 0xFF;
            type = (b0 >> 1) & 0x3F;
            int layer = ((b0 & 1) << 5) | (b1 >> 3);
            if (layer != 0 || (b1 & 0x07) == 0) {
                return null;  // nuh_layer_id 不是 0，或者 nuh_temporal_id_plus1 是 0
            }
            boolean vcl = type <= 9 || (type >= 16 && type <= 21);
            if (!vcl && (type < 32 || type > 40)) {
                return null;  // 保留的类型：编码器不会出
            }
            slice = vcl;
            key = type >= 16 && type <= 21;
            // VPS / SPS / PPS / 访问单元分隔符、前缀 SEI
            opener = (type >= 32 && type <= 35) || type == 39;
        } else {
            type = b0 & 0x1F;
            int ref = (b0 >> 5) & 0x03;
            if (type < 1 || type > 12) {
                return null;
            }
            if ((type == 5 || type == 7 || type == 8) && ref == 0) {
                return null;  // IDR、SPS、PPS 的 nal_ref_idc 不能是 0
            }
            if ((type == 6 || type >= 9) && ref != 0) {
                return null;  // SEI、分隔符、序列 / 码流结束、填充数据的 nal_ref_idc 必须是 0
            }
            slice = type <= 5;
            key = type == 5;
            // SEI / SPS / PPS / 访问单元分隔符
            opener = type >= 6 && type <= 9;
        }
        boolean newPicture = slice && peek >= headerBytes + 1 && (head[4 + headerBytes] & 0x80) != 0;
        return new Nal(length, slice, key, newPicture, slice ? newPicture : opener);
    }

    /** 从 [from, to) 里找下一段画面开始的地方（{@link #opensRun}）；没有是 -1。 */
    static long resync(Reader in, long from, long to, long end, boolean hevc) throws IOException {
        for (long p = from; p < to && p + 4 <= end; p++) {
            if (opensRun(in, p, end, hevc)) {
                return p;
            }
        }
        return -1;
    }

    /** p 这里开始一段画面：一个能开一帧的 NAL，后面还接得上 {@link #RUN_NALS} - 1 个（或者正好走到头）。 */
    private static boolean opensRun(Reader in, long p, long end, boolean hevc) throws IOException {
        Nal first = readNal(in, p, end, hevc);
        if (first == null || !first.opensPicture) {
            return false;
        }
        long q = p + 4 + first.length;
        for (int i = 1; i < RUN_NALS; i++) {
            if (q == end) {
                return true;
            }
            Nal next = readNal(in, q, end, hevc);
            if (next == null) {
                return false;
            }
            q += 4 + next.length;
        }
        return true;
    }

    /** 随机读文件：带一块 1 MB 的缓存（往前找、回头看一般都在附近）。 */
    static final class Reader implements Closeable {
        private final RandomAccessFile raf;
        private final byte[] block = new byte[1 << 20];
        private long blockStart = -1;
        private int blockLength;
        /** 读 NAL 头用的那几个字节（{@link #readNal}），一个 Reader 一份。 */
        final byte[] head = new byte[8];

        Reader(File file) throws IOException {
            raf = new RandomAccessFile(file, "r");
        }

        /** 从 pos 读 length 个字节到 out[at..]；文件里没有这么多是 false。 */
        boolean read(long pos, byte[] out, int at, int length) throws IOException {
            if (blockStart < 0 || pos < blockStart || pos + length > blockStart + blockLength) {
                raf.seek(pos);
                blockStart = pos;
                blockLength = 0;
                int n;
                while (blockLength < block.length
                        && (n = raf.read(block, blockLength, block.length - blockLength)) > 0) {
                    blockLength += n;
                }
                if (length > blockLength) {
                    return false;
                }
            }
            System.arraycopy(block, (int) (pos - blockStart), out, at, length);
            return true;
        }

        @Override
        public void close() throws IOException {
            raf.close();
        }
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
}
