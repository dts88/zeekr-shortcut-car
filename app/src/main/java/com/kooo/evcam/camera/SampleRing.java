package com.kooo.evcam.camera;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * 录制器内存里的已编码画面：排着队等写盘的，和写过之后多留的一段。同一份数据，不另存副本。
 *
 * <h3>为什么写盘要排队（2026-10-08）</h3>
 *
 * <p>码率「高」、帧率「原始」（约 30 fps）时，录像隔几秒缺一小段，诊断里 StallWatch 记着
 * 「slow write on camera 2」0.3–1.05 秒，约 6 秒一次。以前取帧、编码、写文件都在编码线程上：
 * U 盘刷写缓存时写文件一卡，编码线程跟着停，相机那边的 SurfaceTexture 只留最新一帧，
 * 这段时间的画面就全丢了；帧率越高、码率越大，每次要刷的越多，卡得越久。现在编码线程只把编码好的
 * 样本拷出来排进这里，写入线程按顺序写（{@link CodecVideoRecorder}）。U 盘卡一秒，排队就涨一秒，
 * 画面一帧不丢。</p>
 *
 * <h3>排队有上限</h3>
 *
 * <p>约 {@link #QUEUE_MS} 的画面；字节数另有上限，按目标码率算（{@link #queueBytesFor}）。
 * 满了编码线程先等一小会儿，还满就不编这一帧 —— 最坏和以前一样在相机这一侧丢帧，而不是让内存一直涨。
 * 已经编码的样本一个都不丢：丢掉一个，到下一个关键帧之前都解不出来。</p>
 *
 * <h3>写过之后多留一段（1.47.0 起）</h3>
 *
 * <p>录像盘掉线时（2026-09-26 哨兵模式，固态盘一锁车就从系统里消失），写进文件的最后二十来秒
 * 其实还在系统的写缓存里，盘一没它们就跟着没了 —— 那个文件最后是 0 字节。写过的样本多留在这里，
 * 换到另一个盘之后先把它们补写进去，再接着录，掉盘前后的画面就不丢。</p>
 *
 * <p>留多久：最近 {@link #KEEP_MS} 一定留着；比这更早的，要等写入线程的 fsync 确认它落盘了才丢
 * （{@link #syncPoint} / {@link #markSynced}）。留着的最多 {@link #MAX_MS}，排队的加上留着的
 * 一共不超过 {@link #MAX_BYTES}；超了丢留着的里面最老的 —— fsync 一直不成功时内存不能无限涨。
 * 丢只丢到关键帧：补写要从关键帧开始，否则前面几帧解不出来。时长一律按画面时间戳和开机时长算，
 * 不用墙上时间（车机睡醒后会把墙上时间跳 18–23 秒）。</p>
 *
 * <p>停录时等不到写入线程（卡在一次写 / fsync 里，盘多半没了）：没确认落盘的 —— 留着的和排着的 —— 一次交给抢救线程，
 * 从这里拿掉（{@link #abandon}）。内存不跟着一个卡死的写入线程一直占着。</p>
 *
 * <h3>命令和样本排在一个队里</h3>
 *
 * <p>开文件、开轨、收文件这几条命令和样本按顺序排在一起，写入线程按顺序做：分段切换、停录时
 * 收文件之前，这个文件该写的一定都写完了。命令是什么由录制器自己解释，这里只管顺序。</p>
 *
 * <p>纯 Java，不碰 Android，见 {@code SampleRingTest}。方法都加锁：编码线程放、写入线程取、诊断随时读。</p>
 */
final class SampleRing {

    /** 写过之后最近这么久一定留着（项目拥有者 2026-09-26：「至少缓存了 15 秒的信息」）。 */
    static final long KEEP_MS = 15_000L;
    /** 写过的最多留这么久。 */
    static final long MAX_MS = 60_000L;
    /** 排队的加上写过留着的，最多占这么多内存。环视四宫格 2560×2560 HEVC 实测每分钟 58 MB，够一分钟。 */
    static final long MAX_BYTES = 64L * 1024 * 1024;

    /**
     * 排队最多排这么久的画面（项目所有者 2026-10-08：约 3 秒）。
     * U 盘刷写缓存实测一次卡 0.3–1.05 秒，3 秒兜得住；再长就不是「一阵慢」，是盘跟不上，排多少都没用。
     */
    static final long QUEUE_MS = 3_000L;
    /** 排队字节上限的下限：码率很低的那几路（座舱）由画面时长（3 秒）说了算，字节不先卡住。 */
    static final long QUEUE_MIN_BYTES = 4L * 1024 * 1024;
    /** 排队字节上限的上限：加上写过留着的最近 15 秒，也在 {@link #MAX_BYTES} 以内。 */
    static final long QUEUE_MAX_BYTES = 24L * 1024 * 1024;
    /** 目标码率是平均值，编码器给的是可变码率（画面复杂、关键帧时会冲高）：按两倍留余量。 */
    static final int QUEUE_HEADROOM = 2;

    /**
     * fsync 之前这么久交给 muxer 的样本，fsync 成功后才算落盘。
     *
     * <p>muxer 自己还攒着最近一秒左右的样本没写进文件（它按画面时间成块写），所以两头都留余量：
     * 交给它的时刻早于 fsync 这么久，画面时间也比最新写过的早这么久。一阵慢之后写入线程一口气补写好几秒，
     * 那几秒交给 muxer 的时刻挨得很近，只看时刻会把还在 muxer 手里的当成已落盘。</p>
     */
    static final long SYNC_SLACK_MS = 3_000L;

    /**
     * 排队的字节上限：{@link #QUEUE_MS} 的画面按目标码率算，乘 {@link #QUEUE_HEADROOM}，
     * 夹在 {@link #QUEUE_MIN_BYTES} 和 {@link #QUEUE_MAX_BYTES} 之间。码率「高」的环视（24 Mbps）是 18 MB。
     */
    static long queueBytesFor(long bitrateBps) {
        long bytes = Math.max(0L, bitrateBps) / 8L * QUEUE_MS / 1000L * QUEUE_HEADROOM;
        return Math.max(QUEUE_MIN_BYTES, Math.min(QUEUE_MAX_BYTES, bytes));
    }

    /** 关键帧：MediaCodec.BUFFER_FLAG_KEY_FRAME 的值（这里不引 Android，SampleRingTest 对过）。 */
    static final int FLAG_KEY_FRAME = 1;
    /** 只有参数集的缓冲区：MediaCodec.BUFFER_FLAG_CODEC_CONFIG 的值。 */
    static final int FLAG_CODEC_CONFIG = 2;

    static final class Sample {
        /** 进队的顺序号，一直往上加。落盘记到哪一个，就按它算。 */
        final long seq;
        /** 编码器给的原始时间戳：整场录像单调递增，不按分段归零。 */
        final long ptsUs;
        /** 编码器给的标志位（MediaCodec.BufferInfo.flags），写进 muxer 时原样带上。 */
        final int flags;
        final boolean keyframe;
        /**
         * 只有参数集（输出格式里没带参数集时才当样本排，见 CodecVideoRecorder.skipAsSample）。
         * 它的时间戳常是 0，不是画面时间：算排了多长的画面、这个文件从哪个时间起，都不看它。
         */
        final boolean config;
        final byte[] data;
        /** 拷出来进队的时刻（开机时长，含深睡）。抢救文件按它推算起名用的墙上时间。 */
        final long queuedAtMs;
        /** 交给 muxer 的时刻（开机时长，含深睡）；还没写是 -1。只在锁里读写。 */
        private long writtenAtMs = -1L;

        Sample(long seq, long ptsUs, int flags, byte[] data, long queuedAtMs) {
            this.seq = seq;
            this.ptsUs = ptsUs;
            this.flags = flags;
            this.keyframe = (flags & FLAG_KEY_FRAME) != 0;
            this.config = (flags & FLAG_CODEC_CONFIG) != 0 && !keyframe;
            this.data = data;
            this.queuedAtMs = queuedAtMs;
        }
    }

    /** 排着的：{@link Sample}，或者录制器的一条命令（别的任何对象）。 */
    private final ArrayDeque<Object> queue = new ArrayDeque<>();
    /** 写过、多留着的：从关键帧开始，按顺序。 */
    private final ArrayDeque<Sample> kept = new ArrayDeque<>();
    private long queuedBytes;
    private int queuedSamples;
    private long keptBytes;
    private long queueCapBytes = QUEUE_MIN_BYTES;
    private long nextSeq;
    /** 顺序号不大于它的样本已经 fsync 落盘了。 */
    private long syncedThroughSeq = -1L;
    /** 排队最深到过多少（这次录像里）。 */
    private long highBytes;
    private long highUs;

    /** 排队的字节上限，按这一路的目标码率定（{@link #queueBytesFor}）。 */
    synchronized void setQueueCapBytes(long bytes) {
        queueCapBytes = Math.max(1L, bytes);
        notifyAll();
    }

    synchronized long queueCapBytes() {
        return queueCapBytes;
    }

    // ================================================================= 编码线程这一侧

    /**
     * 排一个刚编码好的样本。总是收下 —— 已经编码的不能丢；排满了的话由编码线程少编几帧（{@link #hasRoom}）。
     *
     * @param flags 编码器给的标志位：关键帧、只有参数集都从这里看（{@link #FLAG_KEY_FRAME}、{@link #FLAG_CODEC_CONFIG}）
     * @param nowMs 开机时长（含深睡）
     */
    synchronized Sample offerSample(long ptsUs, int flags, byte[] data, long nowMs) {
        Sample sample = new Sample(nextSeq++, ptsUs, flags, data, nowMs);
        queue.addLast(sample);
        queuedBytes += data.length;
        queuedSamples++;
        highBytes = Math.max(highBytes, queuedBytes);
        highUs = Math.max(highUs, queuedSpanUs());
        // 一共的上限：排队涨了，写过留着的让出来
        trimKept();
        return sample;
    }

    /** 排一条命令，排在已经排着的样本后面。 */
    synchronized void offerCommand(Object command) {
        if (command instanceof Sample) {
            throw new IllegalArgumentException("samples go through offerSample");
        }
        queue.addLast(command);
    }

    /** 还能不能再编一帧：排着的画面不到 {@link #QUEUE_MS}、字节不到上限。 */
    synchronized boolean hasRoom() {
        return queuedBytes < queueCapBytes && queuedSpanUs() < QUEUE_MS * 1000L;
    }

    /**
     * 排队退到两个上限的一半以下了：「满了」的那一段到此为止（{@link Backpressure#admitted}）。
     * 卡在上限边上时编一帧、丢一帧来回跳，不能每跳一下就算新的一段。
     */
    synchronized boolean isDrained() {
        return queuedBytes <= queueCapBytes / 2 && queuedSpanUs() <= QUEUE_MS * 1000L / 2;
    }

    /**
     * 等到有空位，最多等 timeoutMs（编码线程上）。写入线程写走一个样本就叫醒它。
     *
     * @return 有空位了
     */
    synchronized boolean awaitRoom(long timeoutMs) throws InterruptedException {
        long deadline = System.nanoTime() + Math.max(0L, timeoutMs) * 1_000_000L;
        while (!hasRoom()) {
            long leftMs = (deadline - System.nanoTime()) / 1_000_000L;
            if (leftMs <= 0) {
                return false;
            }
            wait(leftMs);
        }
        return true;
    }

    // ================================================================= 写入线程这一侧

    /** 队头：下一个要写的样本，或者下一条要做的命令；空了是 null。不出队，做完了调 done / written / discard。 */
    synchronized Object peek() {
        return queue.peekFirst();
    }

    /** 这条命令做完了，出队。 */
    synchronized void done(Object command) {
        removeEntry(command);
    }

    /**
     * 这个样本交给 muxer 了：出队，转到「写过的」那一段。那一段从关键帧开始 ——
     * 一个文件开头、关键帧之前的不留，补写时它们也解不出来。
     *
     * @param nowMs 开机时长（含深睡）
     */
    synchronized void written(Sample sample, long nowMs) {
        if (!removeQueued(sample)) {
            // 已经不在队里了：录制器放弃等写入线程时连同它一起交给了抢救（{@link #abandon}），或者整个清掉了。不再留
            return;
        }
        sample.writtenAtMs = nowMs;
        if (!kept.isEmpty() || sample.keyframe) {
            kept.addLast(sample);
            keptBytes += sample.data.length;
        }
        trimKept();
        notifyAll();
    }

    /** 这个样本写不了（这个文件没开成、换盘也没换成）：出队丢掉。 */
    synchronized void discard(Sample sample) {
        if (removeQueued(sample)) {
            notifyAll();
        }
    }

    /** 写过多留的那一段，按顺序，第一个是关键帧（换盘补写、抢救用）。 */
    synchronized List<Sample> keptSnapshot() {
        return new ArrayList<>(kept);
    }

    synchronized boolean keptIsEmpty() {
        return kept.isEmpty();
    }

    /**
     * 写过多留的那一段刚被补写进一个新文件（换盘）：新盘上还没落盘，按现在交给 muxer 的算，
     * 等新盘上的 fsync 确认了才能丢。
     */
    synchronized void rewritten(long nowMs) {
        if (kept.isEmpty()) {
            return;
        }
        for (Sample sample : kept) {
            sample.writtenAtMs = nowMs;
        }
        syncedThroughSeq = Math.min(syncedThroughSeq, kept.peekFirst().seq - 1);
    }

    /** 文件收好了（或者没收好、已经抢救过了）：写过多留的那一段不再需要。 */
    synchronized void clearKept() {
        kept.clear();
        keptBytes = 0;
    }

    /**
     * 现在开始 fsync 的话，成功之后哪些样本算落盘：交给 muxer 的时刻早于 nowMs 至少 {@link #SYNC_SLACK_MS}、
     * 画面时间也比最新写过的早至少这么久（见 {@link #SYNC_SLACK_MS}）。fsync 之前取，成功了交给 {@link #markSynced}。
     *
     * @param nowMs 开机时长（含深睡）
     * @return 最后一个算落盘的样本的顺序号；一个都不算是 -1
     */
    synchronized long syncPoint(long nowMs) {
        if (kept.isEmpty()) {
            return -1L;
        }
        long newestPtsUs = kept.peekLast().ptsUs;
        long point = -1L;
        for (Sample sample : kept) {
            if (sample.writtenAtMs > nowMs - SYNC_SLACK_MS
                    || sample.ptsUs > newestPtsUs - SYNC_SLACK_MS * 1000L) {
                break;
            }
            point = sample.seq;
        }
        return point;
    }

    /** fsync 成功了：顺序号不大于 seq 的都已落盘，早于最近 {@link #KEEP_MS} 的可以丢了。 */
    synchronized void markSynced(long seq) {
        if (seq > syncedThroughSeq) {
            syncedThroughSeq = seq;
        }
        trimKept();
    }

    /**
     * 写入线程卡住、录制器不再等它了：内存里还没确认落盘的 —— 写过多留的那一段，接着是排着的（样本和命令，按原来的顺序）——
     * 一次交出来给抢救线程，样本从这里拿掉，内存马上腾出来。
     *
     * <p>命令留在队里：写入线程回过神来照样收文件、退出。它手里正写着的那一个，回来时已经不在队里，不再留（{@link #written}）；
     * 拿掉的样本它也不会再写 —— 那些由抢救线程写到别的盘上。</p>
     */
    synchronized List<Object> abandon() {
        List<Object> out = new ArrayList<>(kept.size() + queue.size());
        out.addAll(kept);
        out.addAll(queue);
        kept.clear();
        keptBytes = 0;
        Iterator<Object> it = queue.iterator();
        while (it.hasNext()) {
            if (it.next() instanceof Sample) {
                it.remove();
            }
        }
        queuedBytes = 0;
        queuedSamples = 0;
        notifyAll();
        return out;
    }

    /** 全部清掉（开录、释放）。 */
    synchronized void clear() {
        queue.clear();
        kept.clear();
        queuedBytes = 0;
        queuedSamples = 0;
        keptBytes = 0;
        syncedThroughSeq = -1L;
        highBytes = 0;
        highUs = 0;
        notifyAll();
    }

    // ================================================================= 诊断

    /** 排着等写的样本数（命令不算）。 */
    synchronized int queuedSamples() {
        return queuedSamples;
    }

    synchronized long queuedBytes() {
        return queuedBytes;
    }

    /** 排着等写的有多长的画面（毫秒）。 */
    synchronized long queuedMs() {
        return queuedSpanUs() / 1000L;
    }

    /** 这次录像里排队最多到过多少字节。 */
    synchronized long highBytes() {
        return highBytes;
    }

    /** 这次录像里排队最长到过多长的画面（毫秒）。 */
    synchronized long highMs() {
        return highUs / 1000L;
    }

    synchronized long keptBytes() {
        return keptBytes;
    }

    /** 写过多留的那一段有多长的画面（毫秒）。 */
    synchronized long keptMs() {
        return keptSpanUs() / 1000L;
    }

    // ================================================================= 内部

    private boolean removeQueued(Sample sample) {
        if (!removeEntry(sample)) {
            return false;
        }
        queuedBytes -= sample.data.length;
        queuedSamples--;
        return true;
    }

    /** 出队：一般就是队头；不是的话按对象找（队清过时它已经不在了）。 */
    private boolean removeEntry(Object entry) {
        if (queue.peekFirst() == entry) {
            queue.pollFirst();
            return true;
        }
        Iterator<Object> it = queue.iterator();
        while (it.hasNext()) {
            if (it.next() == entry) {
                it.remove();
                return true;
            }
        }
        return false;
    }

    /**
     * 排着的第一个画面到最后一个画面隔了多久（画面时间，微秒）。命令不算；只有参数集的样本也不算 ——
     * 它的时间戳常是 0，第二段起画面的时间戳早过了 60 秒，算进来排队就像一下子排了一分钟。
     */
    private long queuedSpanUs() {
        Sample first = null;
        for (Object entry : queue) {
            if (entry instanceof Sample && !((Sample) entry).config) {
                first = (Sample) entry;
                break;
            }
        }
        if (first == null) {
            return 0L;
        }
        Sample last = first;
        Iterator<Object> it = queue.descendingIterator();
        while (it.hasNext()) {
            Object entry = it.next();
            if (entry instanceof Sample && !((Sample) entry).config) {
                last = (Sample) entry;
                break;
            }
        }
        return Math.max(0L, last.ptsUs - first.ptsUs);
    }

    private long keptSpanUs() {
        return kept.isEmpty() ? 0L : Math.max(0L, kept.peekLast().ptsUs - kept.peekFirst().ptsUs);
    }

    /** 留存规则；只在关键帧处截断，最后一整段（关键帧到最新）永远留着。 */
    private void trimKept() {
        if (kept.isEmpty()) {
            return;
        }
        // 1. 已落盘、又早于最新写过的 KEEP_MS 的丢掉：从头数，截到「前面的都能丢」的最后一个关键帧
        long keepFromUs = kept.peekLast().ptsUs - KEEP_MS * 1000L;
        int cut = 0;
        int i = 0;
        for (Sample sample : kept) {
            if (sample.keyframe) {
                cut = i;
            }
            if (sample.ptsUs >= keepFromUs || sample.seq > syncedThroughSeq) {
                break;
            }
            i++;
        }
        dropKept(cut);

        // 2. 超过上限（排队的和留着的一起算）：整段整段（关键帧到下一个关键帧）丢最老的
        while (keptBytes + queuedBytes > MAX_BYTES || keptSpanUs() > MAX_MS * 1000L) {
            int next = secondKeyframeIndex();
            if (next < 0) {
                break;
            }
            dropKept(next);
        }
    }

    private int secondKeyframeIndex() {
        int i = 0;
        for (Sample sample : kept) {
            if (i > 0 && sample.keyframe) {
                return i;
            }
            i++;
        }
        return -1;
    }

    private void dropKept(int count) {
        for (int i = 0; i < count; i++) {
            Sample sample = kept.pollFirst();
            if (sample == null) {
                break;
            }
            keptBytes -= sample.data.length;
        }
    }

    // ================================================================= 排满了的那几段

    /**
     * 排队满了、编码线程丢相机帧的那几段：什么时候开始、满了多久、丢了几帧、排到多深。
     *
     * <p>一段从丢第一帧算起，到排队退到一半以下（{@link SampleRing#isDrained}）为止 ——
     * 卡在上限边上时编一帧、丢一帧来回跳，那还是同一段。</p>
     *
     * <p>黑匣子照 {@code BlackBox.count} 的办法记：第一段一结束就记一行；之后的只累加，
     * 离上一行 {@link #NOTE_GAP_MS} 以上才汇总一行；停录时把没记的补上（{@link #flush}）。
     * U 盘一直跟不上时一段接一段，逐段记会把黑匣子刷满。只在编码线程上用；总数给诊断随时读。</p>
     */
    static final class Backpressure {

        /** 两行之间至少隔这么久（和 BlackBox 汇总计数的间隔一样）。 */
        static final long NOTE_GAP_MS = 5 * 60 * 1000L;

        /** 要记的一行：从上一行到现在的几段加起来。 */
        static final class Summary {
            final int episodes;
            final long fullMs;
            final long droppedFrames;
            final long deepestMs;
            final long deepestBytes;

            Summary(int episodes, long fullMs, long droppedFrames, long deepestMs, long deepestBytes) {
                this.episodes = episodes;
                this.fullMs = fullMs;
                this.droppedFrames = droppedFrames;
                this.deepestMs = deepestMs;
                this.deepestBytes = deepestBytes;
            }
        }

        /** 这一段从什么时候满的；-1 = 没满。 */
        private long fullSinceMs = -1L;
        // 还没记进黑匣子的
        private int episodes;
        private long fullMs;
        private long dropped;
        private long deepestMs;
        private long deepestBytes;
        /** 上一行什么时候记的；-1 = 这次录像还没记过。 */
        private long lastNoteMs = -1L;
        // 这次录像一共，给诊断读
        private volatile int totalEpisodes;
        private volatile long totalDropped;

        /** 开录：从头算。 */
        void reset() {
            fullSinceMs = -1L;
            episodes = 0;
            fullMs = 0;
            dropped = 0;
            deepestMs = 0;
            deepestBytes = 0;
            lastNoteMs = -1L;
            totalEpisodes = 0;
            totalDropped = 0;
        }

        /**
         * 排满了，丢了一帧。
         *
         * @param nowMs 开机时长
         * @return 这是新的一段的开头（提示用户就在这时候）
         */
        boolean dropped(long nowMs, long queuedMs, long queuedBytes) {
            boolean started = fullSinceMs < 0;
            if (started) {
                fullSinceMs = nowMs;
                episodes++;
                totalEpisodes++;
            }
            dropped++;
            totalDropped++;
            deepestMs = Math.max(deepestMs, queuedMs);
            deepestBytes = Math.max(deepestBytes, queuedBytes);
            return started;
        }

        /**
         * 有空位了，编了一帧。排队已经退到一半以下（drained）这一段才算结束；
         * 结束了、又该记黑匣子了，返回要记的那一行；不该记是 null。
         */
        Summary admitted(long nowMs, boolean drained) {
            if (fullSinceMs < 0 || !drained) {
                return null;
            }
            fullMs += Math.max(0L, nowMs - fullSinceMs);
            fullSinceMs = -1L;
            if (lastNoteMs >= 0 && nowMs - lastNoteMs < NOTE_GAP_MS) {
                return null;
            }
            return take(nowMs);
        }

        /** 停录：还满着的那一段到此为止；没记的都交出来，没有是 null。 */
        Summary flush(long nowMs) {
            if (fullSinceMs >= 0) {
                fullMs += Math.max(0L, nowMs - fullSinceMs);
                fullSinceMs = -1L;
            }
            return episodes == 0 ? null : take(nowMs);
        }

        boolean isFull() {
            return fullSinceMs >= 0;
        }

        int totalEpisodes() {
            return totalEpisodes;
        }

        long totalDropped() {
            return totalDropped;
        }

        private Summary take(long nowMs) {
            Summary summary = new Summary(episodes, fullMs, dropped, deepestMs, deepestBytes);
            episodes = 0;
            fullMs = 0;
            dropped = 0;
            deepestMs = 0;
            deepestBytes = 0;
            lastNoteMs = nowMs;
            return summary;
        }
    }
}
