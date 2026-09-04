package com.kooo.evcam.profile;

import java.util.ArrayList;
import java.util.List;

/**
 * 一路相机在这份配置里怎么用。
 *
 * <h3>role 不是用户填的</h3>
 *
 * <p>「哪一路是环视合成流」由 {@code ZeekrCameraLocator} 探测出来 —— 靠
 * 1280×5140 这种只有合成流才会给的长条。配置里记的是<b>角色</b>，不是相机 id：
 * 换台车、id 变了，配置照样对得上。</p>
 */
public final class CameraProfile {

    /** 环视合成流那一路。 */
    public static final String ROLE_COMPOSITE = "composite";

    /** 座舱相机，按探测顺序编号。 */
    public static final String ROLE_CABIN_1 = "cabin1";
    public static final String ROLE_CABIN_2 = "cabin2";

    public String role = ROLE_COMPOSITE;

    /** 这一路参不参与。关掉的相机不开、不录、不占流。 */
    public boolean enabled = true;

    public StreamSpec preview = StreamSpec.preview(StreamSpec.RESOLUTION_AUTO);
    public StreamSpec record = new StreamSpec();
    public StreamSpec photo = StreamSpec.photo(StreamSpec.RESOLUTION_MAX, 95);

    /**
     * 这一路的画面在主界面上分成几格、各摆在哪里。
     *
     * <p>合成流拆四格就是四条；普通相机就一条（{@code laneIndex = -1}）。
     * 拆不拆由 {@code StreamLayoutTable} 决定，这里只管摆放。</p>
     */
    public final List<LaneLayout> lanes = new ArrayList<>();

    public CameraProfile(String role) {
        this.role = role;
    }

    public CameraProfile() {
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(role).append(enabled ? "" : "（未启用）").append('\n');
        sb.append("    预览  ").append(preview.resolution).append('\n');
        sb.append("    录制  ").append(record).append('\n');
        sb.append("    拍照  ").append(photo.resolution)
                .append("  质量 ").append(photo.jpegQuality).append('\n');
        for (LaneLayout lane : lanes) {
            sb.append("    摆放  ").append(lane).append('\n');
        }
        return sb.toString();
    }
}
