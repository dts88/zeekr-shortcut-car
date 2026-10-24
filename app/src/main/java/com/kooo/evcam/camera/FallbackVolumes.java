package com.kooo.evcam.camera;

import com.kooo.evcam.StorageHelper;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 录像盘写不进了，换到哪个盘；换过之后写回哪个盘：只管排顺序、挑盘，挂着哪些盘、剩多少空间由调用者给。
 *
 * <p>换盘的顺序：设置里选的那个盘（它回来了就回去）→ 其余挂着的 U 盘按剩余空间从大到小 → 开发者选项打开着时
 * 最后是内置存储（降级，项目所有者 2026-10-24：只在开发者选项打开时）。这次录像里已经写不进的盘不再试。</p>
 */
final class FallbackVolumes {

    interface FreeBytes {
        long of(File root);
    }

    private FallbackVolumes() {
    }

    /**
     * @param mounted  此刻挂着的 U 盘根目录
     * @param chosen   设置里选的盘的路径，可为 null
     * @param dead     这次录像里写不进的卷名（{@link StorageHelper#volumeOf}）
     * @param free     查剩余空间
     * @param internal 开发者选项打开着时内置存储的根目录，否则 null
     * @return 依次可试的根目录
     */
    static List<File> rank(List<File> mounted, String chosen, Set<String> dead, FreeBytes free, File internal) {
        List<File> out = new ArrayList<>();
        for (File root : mounted) {
            if (!dead.contains(StorageHelper.volumeOf(root.getAbsolutePath()))) {
                out.add(root);
            }
        }
        out.sort((a, b) -> Long.compare(free.of(b), free.of(a)));
        if (chosen != null) {
            for (int i = 0; i < out.size(); i++) {
                if (out.get(i).getAbsolutePath().equals(chosen)) {
                    out.add(0, out.remove(i));
                    break;
                }
            }
        }
        if (internal != null && !dead.contains(StorageHelper.volumeOf(internal.getAbsolutePath()))) {
            out.add(internal);
        }
        return out;
    }

    /**
     * 存储位置选的那个 U 盘此刻挂着没有：挂着就是它的根目录，否则 null。只看挂载表。
     *
     * @param mounted      此刻挂着的 U 盘根目录
     * @param pinned       设置里指定的那个盘的路径；没指定（自动找）为 null 或空
     * @param lastDetected 自动找时上一次找到的那个盘的路径，可为 null：它挂着就是它，不然是挂着的第一个
     */
    static File home(List<File> mounted, String pinned, String lastDetected) {
        if (pinned != null && !pinned.isEmpty()) {
            for (File root : mounted) {
                if (root.getAbsolutePath().equals(pinned)) {
                    return root;
                }
            }
            return null;
        }
        for (File root : mounted) {
            if (root.getAbsolutePath().equals(lastDetected)) {
                return root;
            }
        }
        return mounted.isEmpty() ? null : mounted.get(0);
    }
}
