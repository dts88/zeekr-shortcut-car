package com.kooo.evcam.share;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** 手机上那一页按文件类型走哪条：图片、视频直接显示，别的（诊断报告）给下载按钮。 */
public class FileShareServerTest {

    @Test
    public void picturesAndVideosAreShownInline() {
        assertTrue(FileShareServer.isMedia("20260926_100000_surround.mp4"));
        assertTrue(FileShareServer.isMedia("20260926_100000_surround.JPG"));
        assertTrue(FileShareServer.isMedia("a.png"));
    }

    @Test
    public void aReportIsADownload() {
        assertFalse(FileShareServer.isMedia("zeekr_diagnostics_20260926_100059.json"));
        assertEquals("application/json; charset=utf-8",
                FileShareServer.mimeTypeOf("zeekr_diagnostics_20260926_100059.json"));
        assertEquals("text/plain; charset=utf-8", FileShareServer.mimeTypeOf("evcam.log"));
        assertEquals("application/octet-stream", FileShareServer.mimeTypeOf("x.bin"));
    }
}
