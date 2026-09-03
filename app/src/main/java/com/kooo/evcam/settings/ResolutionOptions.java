package com.kooo.evcam.settings;

import com.kooo.evcam.zeekr.CompositeStreamGeometry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 录制分辨率能选哪些。
 *
 * <h3>取交集，不取并集</h3>
 *
 * <p>只列出<b>每一路摄像头都声明过</b>的分辨率。用并集的话，列表里会出现
 * 只有某一路才有的尺寸：选中之后，没有这个尺寸的那几路会悄悄退回最接近的一个 ——
 * 界面上写着一个值，实际用的是另一个。</p>
 *
 * <h3>排除合成流的尺寸</h3>
 *
 * <p>1280×5140 这类条带尺寸也一并排除：环视那一路固定用探测到的尺寸，
 * 这个设置本来就管不到它，列出来只会让人以为能选。</p>
 *
 * <p>这里按<b>长宽比</b>判断条带，不理会开发者选项里的声明 —— 否则声明
 * 3840×2160 之后，这个尺寸会从座舱相机的可选列表里一起消失。</p>
 *
 * <p>从设置界面里抽出来单独放，是因为这两条规则值得用测试钉住 ——
 * 选错的表现是「录出来的分辨率和设置里写的不一样」，肉眼很难发现。</p>
 */
public final class ResolutionOptions {

    private ResolutionOptions() {
    }

    /**
     * 各路摄像头都支持的分辨率，从大到小排。
     *
     * @param perCamera 每一路各自支持的尺寸，元素为 {@code {宽, 高}}
     * @return 形如 {@code "1920x1080"} 的列表；没有交集时为空
     */
    public static List<String> common(List<List<int[]>> perCamera) {
        Set<String> shared = new LinkedHashSet<>();
        boolean first = true;

        if (perCamera != null) {
            for (List<int[]> sizes : perCamera) {
                Set<String> ofThisCamera = new LinkedHashSet<>();
                if (sizes != null) {
                    for (int[] size : sizes) {
                        if (size == null || size.length < 2) {
                            continue;
                        }
                        if (CompositeStreamGeometry.looksLikeCompositeByRatio(size[0], size[1])) {
                            continue;
                        }
                        ofThisCamera.add(size[0] + "x" + size[1]);
                    }
                }
                if (first) {
                    shared.addAll(ofThisCamera);
                    first = false;
                } else {
                    shared.retainAll(ofThisCamera);
                }
            }
        }

        List<String> sorted = new ArrayList<>(shared);
        Collections.sort(sorted, (a, b) -> pixels(b) - pixels(a));
        return sorted;
    }

    private static int pixels(String resolution) {
        int cross = resolution.indexOf('x');
        if (cross <= 0) {
            return 0;
        }
        try {
            return Integer.parseInt(resolution.substring(0, cross))
                    * Integer.parseInt(resolution.substring(cross + 1));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
