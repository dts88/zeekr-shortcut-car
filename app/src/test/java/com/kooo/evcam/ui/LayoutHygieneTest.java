package com.kooo.evcam.ui;

import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 按钮得是我们那套按钮。
 *
 * <h3>为什么要钉</h3>
 *
 * <p>0.43.0 之前，车机上的图片回看一直是重做之前的样子：菜单键是「☰」字符、
 * 主页键是「⌂」，按钮是原生 {@code <Button>} 靠 {@code android:backgroundTint} 上色，
 * 看起来是圆的。那份布局在 `layout-sw600dp/` 里躺了四个月没人看见 ——
 * 因为没有任何东西会因为它而失败。</p>
 *
 * <p>所以：核心界面里不许出现原生 {@code <Button>}，也不许用
 * {@code android:backgroundTint} 给按钮上色。要按钮就用 {@code MaterialButton} +
 * {@code Widget.Cam.*}；实心色块用 Material 的 {@code app:backgroundTint}
 * （它走主题的着色，不是直接糊一层颜色）。</p>
 *
 * <p>排查用的那些界面不在此列，名单见 {@link CoreLayouts}。</p>
 */
public class LayoutHygieneTest {

    @Test
    public void coreLayoutsUseOurButtons() throws IOException {
        File res = CoreLayouts.resRoot();
        assumeTrue("定位不到资源目录，跳过", res != null);
        List<String> plainButtons = new ArrayList<>();
        List<String> tinted = new ArrayList<>();
        for (Path path : CoreLayouts.all(res)) {
            if (!CoreLayouts.isCore(path)) {
                continue;
            }
            String source = CoreLayouts.read(path);
            if (source.contains("<Button")) {
                plainButtons.add(CoreLayouts.name(res, path));
            }
            if (source.contains("android:backgroundTint")) {
                tinted.add(CoreLayouts.name(res, path));
            }
        }
        assertTrue("这些布局用了原生 <Button>，请改用 MaterialButton + Widget.Cam.*: " + plainButtons,
                plainButtons.isEmpty());
        assertTrue("这些布局用 android:backgroundTint 给按钮上色，请改用 app:backgroundTint 或行样式: "
                + tinted, tinted.isEmpty());
    }
}
