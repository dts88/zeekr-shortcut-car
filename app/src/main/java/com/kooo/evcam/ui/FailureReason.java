package com.kooo.evcam.ui;

import android.content.Context;

import com.kooo.evcam.R;

import java.io.FileNotFoundException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

/**
 * 界面上「X 失败：原因」里的那个原因。
 *
 * <h3>为什么不直接显示异常</h3>
 *
 * <p>异常的原文（{@code getMessage()}、类名）是系统的英文原话，中文、马来文界面上照样是英文，
 * 有时干脆是 {@code null} —— 于是界面上出现过「分享失败：null」。原文只该进日志。</p>
 *
 * <h3>只认几种常见情况</h3>
 *
 * <p>没网、连接超时、存储空间不足、文件不存在：这几种能用当前语言说清，用户也知道怎么办。
 * 其余一律返回 {@code null}，调用方改用不带原因的那句（「无法 X」）。
 * 异常本身由调用方写进 {@code AppLog}，这里不记。</p>
 */
public final class FailureReason {

    /** 异常链最多往里看几层：防止 cause 绕成环。 */
    private static final int MAX_DEPTH = 8;

    private FailureReason() {
    }

    /** 当前语言的原因；说不清时返回 null。 */
    public static String of(Context context, Throwable error) {
        int res = resOf(error);
        return res == 0 ? null : context.getString(res);
    }

    /** 沿异常链从外往里找第一个认得的；0 表示说不清。 */
    static int resOf(Throwable error) {
        Throwable t = error;
        for (int depth = 0; t != null && depth < MAX_DEPTH; depth++, t = t.getCause()) {
            String message = t.getMessage();
            if (t instanceof SocketTimeoutException) {
                return R.string.reason_timeout;
            }
            if (t instanceof UnknownHostException || t instanceof ConnectException
                    || t instanceof NoRouteToHostException
                    || (message != null && message.contains("ENETUNREACH"))) {
                return R.string.reason_no_network;
            }
            // 系统的 I/O 错误把 errno 名字写在消息里，例如「write failed: ENOSPC (No space left on device)」
            if (message != null && message.contains("ENOSPC")) {
                return R.string.reason_no_space;
            }
            // FileNotFoundException 也用来报「没有权限」「只读」（EACCES、EROFS），那些不能说成「文件不存在」
            if (t instanceof FileNotFoundException && (message == null || message.contains("ENOENT"))) {
                return R.string.reason_file_not_found;
            }
        }
        return 0;
    }
}
