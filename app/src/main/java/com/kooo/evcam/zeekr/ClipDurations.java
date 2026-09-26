package com.kooo.evcam.zeekr;

import android.media.MediaMetadataRetriever;

import com.kooo.evcam.AppLog;

import java.io.File;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 录像分段的时长，读一次记住。
 *
 * <h3>为什么要记</h3>
 *
 * <p>时间轴要按时长把分段接起来，而时长只能靠 {@link MediaMetadataRetriever} 一个个文件去读 ——
 * 那是整个扫描里唯一慢的一步。切一次黑白模式，系统会把界面重建一遍，
 * 于是「正在扫描」又从头来一次：文件没变，答案也不会变，重读纯属白等。</p>
 *
 * <h3>为什么只记时长，不记整张列表</h3>
 *
 * <p>目录里有哪些文件<b>会</b>变（刚录完一段、删掉一段），所以每次都重新列目录 ——
 * 那一步本来就快。一个文件的时长不会变，除非它被换成了另一个文件，
 * 而那会带着新的大小和修改时间，也就换了一把钥匙。</p>
 *
 * <p>记在进程里，应用退出就没了：这是省一次等待，不是持久缓存，
 * 不值得为它往磁盘上写东西。</p>
 */
final class ClipDurations {

    /** 键里带上大小和修改时间：同名文件被覆盖之后，旧答案自然对不上。 */
    private static final Map<String, Long> CACHE = new ConcurrentHashMap<>();

    private static final String TAG = "ClipDurations";

    private ClipDurations() {
    }

    /** 读不出来返回 -1，调用方会跳过这个文件（失败不记，下次还能再试）。 */
    static long of(File file) {
        String key = file.getAbsolutePath() + "|" + file.length() + "|" + file.lastModified();
        Long known = CACHE.get(key);
        if (known != null) {
            return known;
        }
        long measured = measure(file);
        if (measured > 0) {
            CACHE.put(key, measured);
        }
        return measured;
    }

    private static long measure(File file) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(file.getAbsolutePath());
            String value = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            return value != null ? Long.parseLong(value) : -1L;
        } catch (Exception e) {
            // 断电、掉盘留下的半截文件读不出时长很常见，不算警告：
            // 算警告的话一打开回放就是几十条，把警告文件里真正要看的冲掉
            AppLog.d(TAG, "读取时长失败: " + file.getName());
            return -1L;
        } finally {
            try {
                retriever.release();
            } catch (Exception ignored) {
                // 释放失败无所谓
            }
        }
    }
}
