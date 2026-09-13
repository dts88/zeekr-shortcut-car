package com.kooo.evcam.camera;

/**
 * 裁剪和平移是用户在<b>转过之后的画面</b>上设的，绘制却发生在<b>源画面</b>的
 * 坐标系里。这个类做那一次换算。
 *
 * <h3>为什么这是个 bug，不是使用习惯问题</h3>
 *
 * <p>一格转 90° 之后，「上边裁 20%」裁掉的是屏幕上的<b>左边</b>：用户按自己看到的
 * 画面填数，代码按源画面理解，两边差一次旋转。转 180° 时上下左右还全反着。
 * 旋转对、裁剪对，合起来就不对 —— 从用户那边看，就是一转就「遮罩失效」。</p>
 *
 * <p>平移和缩放同理：转 90° 之后「往右挪」挪的是上下，「横向放大」放大的是纵向。</p>
 *
 * <h3>角度的方向</h3>
 *
 * <p>旋转角是<b>顺时针</b>的度数 —— 和 {@code Matrix.postRotate} 在 y 轴朝下的
 * 屏幕坐标系里的方向一致。所以转 90° 时源画面的左边会落到屏幕的上边。</p>
 *
 * <p>纯函数，不碰 Android，可以单独测。</p>
 */
public final class LaneOrientation {

    /**
     * 裁剪暂时停用。
     *
     * <h3>为什么是关掉而不是修好</h3>
     *
     * <p>0.44.1 起，只要给环视的某一格设了裁剪，主界面就出问题：先是那一格变雪花，
     * 换了一种裁法之后变成画面互相盖、左右两格不见了。旋转、镜像、缩放平移都正常，
     * 只有裁剪会这样。</p>
     *
     * <p>连着两版靠推理去改这段绘制，两次都改坏了别的东西 —— 说明这条路上有一件
     * 我在代码里看不出来的事（多半和 TextureView 在被变换过的画布上如何被裁有关），
     * 而它只有在车上才看得见。在能看见它之前继续改，是在拿主界面赌。</p>
     *
     * <p>所以：裁剪的值照旧存着（不动用户已经填的数），但绘制时一律当 0。
     * 配置编辑里那一行也标成暂不可用。查清之后把这个常量改回 true 就行。
     * 详见 {@code docs/profile-todo.md}。</p>
     */
    public static final boolean CROP_SUPPORTED = false;

    /** 换算到源画面坐标系之后的裁剪比例。 */
    public final float cropTop;
    public final float cropBottom;
    public final float cropLeft;
    public final float cropRight;

    /** 换算之后的缩放：转四分之一圈时横纵对调。 */
    public final float scaleX;
    public final float scaleY;

    /** 换算之后的平移，仍然是「画面往哪挪」而不是「取景窗往哪挪」。 */
    public final float translateX;
    public final float translateY;

    private LaneOrientation(float cropTop, float cropBottom, float cropLeft, float cropRight,
                            float scaleX, float scaleY, float translateX, float translateY) {
        this.cropTop = cropTop;
        this.cropBottom = cropBottom;
        this.cropLeft = cropLeft;
        this.cropRight = cropRight;
        this.scaleX = scaleX;
        this.scaleY = scaleY;
        this.translateX = translateX;
        this.translateY = translateY;
    }

    /** 转到 0/90/180/270 里最近的那一档；负数和超过一圈的都收进来。 */
    public static int normalise(int rotation) {
        int turns = Math.round(((rotation % 360) + 360) % 360 / 90f) % 4;
        return turns * 90;
    }

    /** 是否转了四分之一圈 —— 长宽要对调的那两档。 */
    public static boolean quarterTurn(int rotation) {
        int normalised = normalise(rotation);
        return normalised == 90 || normalised == 270;
    }

    /**
     * 把显示坐标系里的这几个值换算到源画面坐标系。
     *
     * @param rotation 顺时针旋转的度数
     */
    public static LaneOrientation sourceSpace(int rotation,
                                              float cropTop, float cropBottom,
                                              float cropLeft, float cropRight,
                                              float scaleX, float scaleY,
                                              float translateX, float translateY) {
        switch (normalise(rotation)) {
            case 90:
                // 源左→屏上、源上→屏右、源右→屏下、源下→屏左
                return new LaneOrientation(cropRight, cropLeft, cropTop, cropBottom,
                        scaleY, scaleX, translateY, -translateX);
            case 180:
                return new LaneOrientation(cropBottom, cropTop, cropRight, cropLeft,
                        scaleX, scaleY, -translateX, -translateY);
            case 270:
                // 源左→屏下、源上→屏左、源右→屏上、源下→屏右
                return new LaneOrientation(cropLeft, cropRight, cropBottom, cropTop,
                        scaleY, scaleX, -translateY, translateX);
            default:
                return new LaneOrientation(cropTop, cropBottom, cropLeft, cropRight,
                        scaleX, scaleY, translateX, translateY);
        }
    }
}
