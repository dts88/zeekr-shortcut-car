package com.kooo.evcam;

import android.content.Context;
import android.os.Build;
import android.os.Environment;
import android.os.StatFs;

import java.io.File;

/**
 * 存储帮助类
 * 提供U盘检测和存储路径管理功能
 * 
 * 性能优化：使用内存缓存减少重复的文件系统 I/O 操作
 */
public class StorageHelper {
    private static final String TAG = "StorageHelper";
    
    // 存储目录名称
    public static final String VIDEO_DIR_NAME = "EVCam_Video";
    public static final String PHOTO_DIR_NAME = "EVCam_Photo";
    public static final String LOG_DIR_NAME = "EVCam_Log";
    
    // ==================== 内存缓存（性能优化）====================
    // U盘检测结果缓存（避免重复的文件系统 I/O）
    private static volatile Boolean cachedHasSdCard = null;
    private static volatile File cachedSdCardRoot = null;
    private static volatile long cacheTimestamp = 0;
    private static final long CACHE_VALIDITY_MS = 5000;  // 缓存有效期：5秒
    
    // 用于同步的锁对象
    private static final Object cacheLock = new Object();
    
    /** 最近一次录像实际写进的目录。诊断报告按它列文件：设定的盘不在时会改写到别的盘。 */
    private static volatile File lastRecordingDir;

    public static void noteRecordingDir(File dir) {
        lastRecordingDir = dir;
    }

    /**
     * 这次录像写在「不是设定的那个盘」上：{写的盘, 设定的盘}，卷名（如 B905-2EDD）或 emulated；平时为 null。
     *
     * <p>状态条一直显示它，直到这次录像停止 —— 换盘时只弹一次提示的话，人不在车上就看不到
     * （2026-09-26 哨兵模式：固态盘掉线、自动改写到另一个盘，回来的人以为什么都没录上）。</p>
     */
    private static volatile String[] recordingFallback;

    public static void noteRecordingFallback(String writingTo, String chosen) {
        recordingFallback = writingTo == null ? null : new String[]{writingTo, chosen};
    }

    public static String[] recordingFallback() {
        return recordingFallback;
    }

    /** 路径所在的卷：/storage/B905-2EDD/... → B905-2EDD；/storage/emulated/0/... → emulated；认不出返回整条路径。 */
    public static String volumeOf(String path) {
        if (path == null) {
            return "";
        }
        String prefix = "/storage/";
        if (path.startsWith(prefix)) {
            int end = path.indexOf('/', prefix.length());
            return end > 0 ? path.substring(prefix.length(), end) : path.substring(prefix.length());
        }
        return path;
    }

    /** 最近一次录像实际写进的目录；这个进程里还没录过时为 null。 */
    public static File lastRecordingDir() {
        return lastRecordingDir;
    }

    /** 此刻挂着的 U 盘根目录（读 /proc/mounts）。 */
    public static java.util.List<File> mountedVolumes() {
        return listSdCardRootsFromMounts();
    }

    /** 某个盘上录像该放的目录：root/DCIM/EVCam_Video，和 {@link #getVideoDir} 选好盘之后的规则一样。 */
    public static File videoDirOn(File root) {
        return new File(new File(root, Environment.DIRECTORY_DCIM), VIDEO_DIR_NAME);
    }

    /**
     * 此刻系统里挂着哪些 U 盘（读 /proc/mounts），黑匣子用：「XXXX-XXXX, YYYY-YYYY」，一个都没有时「none」。
     *
     * <p>写不进文件、U 盘事件、开始录像时各记一次 —— 要回答的是「那一刻盘还在不在」。</p>
     */
    public static String describeMounts() {
        java.util.List<File> roots = listSdCardRootsFromMounts();
        if (roots.isEmpty()) {
            return "none";
        }
        StringBuilder sb = new StringBuilder();
        for (File root : roots) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(root.getName());
        }
        return sb.toString();
    }

    /**
     * 清除内存缓存（U盘插拔时调用）
     */
    public static void clearCache() {
        synchronized (cacheLock) {
            cachedHasSdCard = null;
            cachedSdCardRoot = null;
            cacheTimestamp = 0;
            AppLog.d(TAG, "U盘检测缓存已清除");
        }
    }
    
    /**
     * 检查缓存是否有效
     */
    private static boolean isCacheValid() {
        return cacheTimestamp > 0 && (System.currentTimeMillis() - cacheTimestamp) < CACHE_VALIDITY_MS;
    }
    
    /**
     * 检测是否有U盘（并且可以写入公共目录）
     * 使用内存缓存，5秒内不重复检测
     * @param context 上下文
     * @return true 如果检测到U盘且可写入
     */
    public static boolean hasExternalSdCard(Context context) {
        // 先检查缓存
        synchronized (cacheLock) {
            if (isCacheValid() && cachedHasSdCard != null) {
                return cachedHasSdCard;
            }
        }
        
        // 缓存无效，执行检测
        File sdCardRoot = getExternalSdCardRoot(context);
        boolean result = false;
        
        if (sdCardRoot != null && sdCardRoot.exists()) {
            // 检查 DCIM 目录是否可写
            File dcimDir = new File(sdCardRoot, Environment.DIRECTORY_DCIM);
            if (!dcimDir.exists()) {
                // 尝试创建 DCIM 目录
                boolean created = dcimDir.mkdirs();
                if (!created) {
                    AppLog.w(TAG, "无法在U盘上创建 DCIM 目录");
                }
            }
            result = dcimDir.exists() && dcimDir.canWrite();
        }
        
        // 更新缓存
        synchronized (cacheLock) {
            cachedHasSdCard = result;
            cacheTimestamp = System.currentTimeMillis();
        }
        
        return result;
    }
    
    /**
     * 检测是否发生了U盘回退
     * 即：用户选择了U盘存储，但U盘不可用 —— 开发者选项开着时实际落在内置存储上，关着时没有地方可存（{@link #footageRoot}）
     * @param context 上下文
     * @return true 如果发生了回退
     */
    public static boolean isSdCardFallback(Context context) {
        if (context == null) return false;
        
        AppConfig config = new AppConfig(context);
        // 只有当用户选择了U盘时才需要检测回退
        if (!config.isUsingExternalSdCard()) {
            return false;
        }
        
        // 检测U盘是否可用
        return !hasExternalSdCard(context);
    }
    
    /**
     * 一个可用的存储卷。
     */
    public static class VolumeInfo {
        /** 卷根目录，例如 /storage/XXXX-XXXX；取不到时为应用专属目录。 */
        public final File root;
        /** 该卷上的应用专属目录（一定可写）。 */
        public final File appDir;
        /** 显示名，例如 "U盘 1 (XXXX-XXXX)"。 */
        public final String label;
        public final long freeBytes;
        public final long totalBytes;

        /** 设置页和诊断报告里的那一行。建的时候就按当前语言排好，用的地方不必再要 Context。 */
        private final String description;

        VolumeInfo(Context context, File root, File appDir, String label,
                   long freeBytes, long totalBytes) {
            this.root = root;
            this.appDir = appDir;
            this.label = label;
            this.freeBytes = freeBytes;
            this.totalBytes = totalBytes;
            this.description = totalBytes <= 0 ? label
                    : context.getString(R.string.storage_volume_desc, label,
                            formatSize(freeBytes), formatSize(totalBytes));
        }

        /** 名称 + 剩余/总容量。 */
        public String describe() {
            return description;
        }
    }

    /**
     * 列出所有<b>外置</b>存储卷。
     *
     * <p>上游只支持「内部存储」和「U盘」两个选项，且实现上取的是
     * {@code getExternalFilesDirs()} 里的第一个非内部卷 —— 插两个盘时第二个永远用不上。
     * 这里把所有卷都列出来，交给用户选。</p>
     *
     * <p>用 {@code getExternalFilesDirs()} 而不是解析 /proc/mounts：前者返回的是
     * 应用一定有权写入的目录，后者能看到更多挂载点但很多写不了。</p>
     */
    public static java.util.List<VolumeInfo> listExternalVolumes(Context context) {
        java.util.List<VolumeInfo> volumes = new java.util.ArrayList<>();
        if (context == null) {
            return volumes;
        }
        java.util.Set<String> seenRoots = new java.util.HashSet<>();

        // 先从 /proc/mounts 找。这一步不能省：本项目的极氪车机上，U 盘就是只能
        // 通过 /proc/mounts 看到 —— getExternalFilesDirs() 里根本没有它，
        // 但读写、回放都正常。之前只用 getExternalFilesDirs 才会出现
        // 「显示未检测到、实际能选也能用」。
        for (File root : listSdCardRootsFromMounts()) {
            if (!seenRoots.add(root.getAbsolutePath())) {
                continue;
            }
            long free = 0L;
            long total = 0L;
            try {
                free = root.getUsableSpace();
                total = root.getTotalSpace();
            } catch (Exception ignored) {
                // 容量取不到不影响使用
            }
            volumes.add(new VolumeInfo(context, root, root,
                    context.getString(R.string.storage_external_named, root.getName()),
                    free, total));
        }

        try {
            File[] externalDirs = context.getExternalFilesDirs(null);
            if (externalDirs == null) {
                return volumes;
            }
            // 下标 0 是内部存储的外部目录，从 1 开始才是真正的外置卷
            for (int i = 1; i < externalDirs.length; i++) {
                File appDir = externalDirs[i];
                if (appDir == null) {
                    continue;
                }
                if (!appDir.exists() && !appDir.mkdirs()) {
                    AppLog.d(TAG, "存储卷 " + i + " 不可用: " + appDir);
                    continue;
                }

                File root = appDir;
                String path = appDir.getAbsolutePath();
                int cut = path.indexOf("/Android/data/");
                if (cut > 0) {
                    File candidate = new File(path.substring(0, cut));
                    if (candidate.exists() && candidate.canRead()) {
                        root = candidate;
                    }
                }

                if (!seenRoots.add(root.getAbsolutePath())) {
                    continue;  // /proc/mounts 已经收过同一个卷
                }

                String name = root.getName();
                String label = name.isEmpty()
                        ? context.getString(R.string.storage_external_n, i)
                        : context.getString(R.string.storage_external_n_named, i, name);

                long free = 0L;
                long total = 0L;
                try {
                    free = appDir.getUsableSpace();
                    total = appDir.getTotalSpace();
                } catch (Exception ignored) {
                    // 容量取不到不影响使用
                }

                volumes.add(new VolumeInfo(context, root, appDir, label, free, total));
            }
        } catch (Exception e) {
            AppLog.e(TAG, "枚举存储卷失败", e);
        }
        AppLog.d(TAG, "检测到 " + volumes.size() + " 个外置存储卷");
        return volumes;
    }

    /**
     * 获取视频存储目录
     * @param context 上下文
     * @param useExternalSd 是否使用U盘
     * @return 视频存储目录；没有地方可存时为 null（见 {@link #footageRoot}）
     */
    public static File getVideoDir(Context context, boolean useExternalSd) {
        return getStorageDir(footageRoot(context, useExternalSd), VIDEO_DIR_NAME, Environment.DIRECTORY_DCIM);
    }

    /**
     * 获取图片存储目录
     * @param context 上下文
     * @param useExternalSd 是否使用U盘
     * @return 图片存储目录；没有地方可存时为 null（见 {@link #footageRoot}）
     */
    public static File getPhotoDir(Context context, boolean useExternalSd) {
        return getStorageDir(footageRoot(context, useExternalSd), PHOTO_DIR_NAME, Environment.DIRECTORY_DCIM);
    }

    /**
     * 获取日志存储目录（导出的诊断报告）。
     *
     * <p>不归 {@link #footageRoot} 管：诊断报告不是录下来的影像，没有 U 盘时照旧写在内置存储上，
     * 没插盘也能导出。</p>
     * @param context 上下文
     * @param useExternalSd 是否使用U盘
     * @return 日志存储目录
     */
    public static File getLogDir(Context context, boolean useExternalSd) {
        File sdCardRoot = useExternalSd ? getExternalSdCardRoot(context) : null;
        if (useExternalSd && sdCardRoot == null) {
            AppLog.w(TAG, "U盘不可用，日志目录回退到内置存储");
        }
        return getStorageDir(sdCardRoot != null ? sdCardRoot : Environment.getExternalStorageDirectory(),
                LOG_DIR_NAME, Environment.DIRECTORY_DOWNLOADS);
    }

    /**
     * 根据 AppConfig 配置获取视频存储目录
     * @param context 上下文
     * @return 视频存储目录；没有地方可存（没有 U 盘、开发者选项没开）时为 null
     */
    public static File getVideoDir(Context context) {
        AppConfig config = new AppConfig(context);
        return getVideoDir(context, config.isUsingExternalSdCard());
    }
    
    /**
     * 获取录制时实际写入的目录
     * 如果启用了中转写入，返回临时目录；否则返回最终存储目录
     *
     * <p>中转写入的临时目录在内置存储上，和 {@link #footageRoot} 是同一道门：中转写入归开发者选项管，
     * 开发者选项关着时按关算（{@code AppConfig.DEVELOPER_KEYS}），走不到这条分支。</p>
     * @param context 上下文
     * @return 录制写入目录；没有地方可存（没有 U 盘、开发者选项没开）时为 null
     */
    public static File getRecordingDir(Context context) {
        AppConfig config = new AppConfig(context);

        // 检查是否应该使用中转写入
        if (config.shouldUseRelayWrite()) {
            // 使用临时目录（内置存储的缓存目录）
            File tempDir = new File(context.getCacheDir(), FileTransferManager.TEMP_VIDEO_DIR);
            if (!tempDir.exists()) {
                if (tempDir.mkdirs()) {
                    AppLog.d(TAG, "创建临时视频目录: " + tempDir.getAbsolutePath());
                } else {
                    AppLog.e(TAG, "创建临时视频目录失败，回退到普通目录");
                    return getVideoDir(context);
                }
            }
            return tempDir;
        }
        
        // 不使用中转写入，直接返回最终存储目录
        return getVideoDir(context);
    }
    
    /**
     * 获取视频的最终存储目录
     * 即使启用了中转写入，这个方法也返回最终的目标目录
     * @param context 上下文
     * @return 最终存储目录；没有地方可存时为 null
     */
    public static File getFinalVideoDir(Context context) {
        AppConfig config = new AppConfig(context);
        return getVideoDir(context, config.isUsingExternalSdCard());
    }
    
    /**
     * 根据 AppConfig 配置获取图片存储目录
     * @param context 上下文
     * @return 图片存储目录；没有地方可存（没有 U 盘、开发者选项没开）时为 null
     */
    public static File getPhotoDir(Context context) {
        AppConfig config = new AppConfig(context);
        return getPhotoDir(context, config.isUsingExternalSdCard());
    }

    /**
     * 影像（录像、照片）放在哪个盘上 —— 只有这一处决定（项目所有者 2026-10-06：不允许存到内置存储）。
     *
     * <p>规则只有一条：设定的 U 盘；没有 U 盘（或者选的就是内置存储，那只有开发者选得了）时，
     * 开发者选项开着（{@link #isInternalStorageAllowed}）才落在内置存储上，否则返回 null ——
     * 没有地方可存，不退到内置存储。</p>
     *
     * <p>录像、照片、锁定清单、开发者选项里的整帧另存，目录都从这里来，拿到 null 就不写；
     * 开录和拍照之前先问 {@link #isRecordingStorageAvailable}（同一条规则），不录、不拍。
     * 回放、存储页读的也是这个目录，拿到 null 就是「这里什么都没有」；存在内置存储上的
     * （开发者选项开着时录、拍的），打开开发者选项照常能看。</p>
     */
    private static File footageRoot(Context context, boolean useExternalSd) {
        if (useExternalSd) {
            // U盘的公共目录（U盘/DCIM/EVCam_Video 或 U盘/DCIM/EVCam_Photo）
            File sdCardRoot = getExternalSdCardRoot(context);
            if (sdCardRoot != null) {
                return sdCardRoot;
            }
        }
        if (!isInternalStorageAllowed()) {
            return null;
        }
        if (useExternalSd) {
            AppLog.w(TAG, "U盘不可用，回退到内置存储");
        }
        return Environment.getExternalStorageDirectory();
    }

    /**
     * 盘上的存储目录：root/父目录/目录名，不存在就建。
     * @param root 盘的根目录；null（没有地方可存）时返回 null
     * @param dirName 目录名称
     * @param parentDirType 父目录类型（如 DCIM, Downloads）
     * @return 存储目录
     */
    private static File getStorageDir(File root, String dirName, String parentDirType) {
        if (root == null) {
            return null;
        }
        File dir = new File(new File(root, parentDirType), dirName);

        // 确保目录存在
        if (!dir.exists()) {
            boolean created = dir.mkdirs();
            if (created) {
                AppLog.d(TAG, "创建存储目录: " + dir.getAbsolutePath());
            } else {
                AppLog.e(TAG, "创建存储目录失败: " + dir.getAbsolutePath());
            }
        }
        
        return dir;
    }
    
    /**
     * 获取U盘根目录（用于写入公共目录）
     * 优化检测逻辑：内存缓存优先 + SharedPreferences缓存 + 无感切换不同U盘
     * @param context 上下文
     * @return U盘根目录，如果没有则返回 null
     */
    /**
     * 内置存储现在允许当作落盘位置吗。
     *
     * <p>只有开发者选项打开时才允许。行车记录是持续写入，而车机闪存
     * 写坏了换不了 —— 这个代价不该由「不熟悉软件、一路点确定」的人承担，
     * 所以它不是一个弹窗能放行的选择，而是默认就不给。</p>
     *
     * <p>选过的「内置存储」在开发者选项关着时本来就按 U 盘算（{@code AppConfig.getStorageLocation}）；
     * 这里管的是选 U 盘、盘却不在时，录像、照片能不能退到内置存储上存（{@link #footageRoot}），
     * 以及设置里那一项能不能选、录制器换盘时最后一站。</p>
     */
    public static boolean isInternalStorageAllowed() {
        return com.kooo.evcam.settings.DeveloperMode.isUnlocked();
    }

    /**
     * 现在有没有地方存录像、照片：有 U 盘，或者开发者选项放行了内置存储（{@link #footageRoot} 的那条规则）。
     *
     * <p>拒录（{@code RecordingCoordinator}）、拍照入口的拒拍（{@code MultiCameraManager.takePhoto}）
     * 和录制键的「不可用」状态问的是同一件事，所以只有这一处判断 —— 各写一遍，
     * 迟早出现「按钮说能录、按下去说不能」。</p>
     */
    public static boolean isRecordingStorageAvailable(Context context) {
        return !willRecordToInternal(context) || isInternalStorageAllowed();
    }

    /**
     * 录像实际上会不会落在内置存储上。
     *
     * <p>两种情况都算：选的就是内置存储（开发者选项开着时才算数），或者选了 U 盘但盘不在
     * （开发者选项开着时会回退到内置，见 {@link #footageRoot}）。</p>
     *
     * <p>后一种尤其值得提醒 —— 用户以为在写 U 盘，实际在写车机闪存，
     * 而这是个不声不响就发生的降级。</p>
     */
    public static boolean willRecordToInternal(Context context) {
        if (context == null) {
            return false;
        }
        if (!new AppConfig(context).isUsingExternalSdCard()) {
            return true;
        }
        // 选了 U 盘但盘不在，走的是 footageRoot 里那条回退分支
        return isSdCardFallback(context);
    }

    public static File getExternalSdCardRoot(Context context) {
        if (context == null) {
            return null;
        }
        
        // 优先检查内存缓存（最快，避免任何 I/O）
        synchronized (cacheLock) {
            if (isCacheValid() && cachedSdCardRoot != null) {
                // 快速验证缓存的路径仍然有效
                if (cachedSdCardRoot.exists() && cachedSdCardRoot.canRead()) {
                    return cachedSdCardRoot;
                }
                // 缓存的路径失效了，清除缓存继续检测
                cachedSdCardRoot = null;
                cachedHasSdCard = null;
            }
        }
        
        // 内存缓存未命中，执行检测
        File result = getExternalSdCardRootInternal(context);
        
        // 更新内存缓存
        synchronized (cacheLock) {
            cachedSdCardRoot = result;
            cacheTimestamp = System.currentTimeMillis();
        }
        
        return result;
    }
    
    /**
     * 实际执行U盘检测（内部方法，不使用缓存）
     */
    private static File getExternalSdCardRootInternal(Context context) {
        AppConfig config = new AppConfig(context);
        
        // 方法0：优先使用用户手动设置的路径
        String customPath = config.getCustomSdCardPath();
        if (customPath != null && !customPath.isEmpty()) {
            File customDir = new File(customPath);
            if (customDir.exists() && customDir.isDirectory() && customDir.canRead()) {
                return customDir;
            }
        }
        
        // 方法1：检测上次 SharedPreferences 缓存的路径（比重新检测快）
        String spCachedPath = config.getLastDetectedSdPath();
        if (spCachedPath != null && !spCachedPath.isEmpty()) {
            File cachedDir = new File(spCachedPath);
            if (cachedDir.exists() && cachedDir.isDirectory() && cachedDir.canRead()) {
                return cachedDir;
            }
            // 缓存的路径不可用了（U盘拔出或更换），继续检测
        }
        
        // 方法2：读取 /proc/mounts（快速可靠，能看到所有挂载的存储设备）
        // 会检测任何 XXXX-XXXX 格式的 SD 卡，实现无感切换
        File sdRoot = getSdCardFromMounts();
        if (sdRoot != null) {
            // 检测到U盘，更新 SharedPreferences 缓存
            config.setLastDetectedSdPath(sdRoot.getAbsolutePath());
            return sdRoot;
        }
        
        // 方法3：通过 getExternalFilesDirs 获取（标准 API）
        sdRoot = getSdCardFromExternalFilesDirs(context);
        if (sdRoot != null) {
            // 检测到U盘，更新 SharedPreferences 缓存
            config.setLastDetectedSdPath(sdRoot.getAbsolutePath());
            return sdRoot;
        }
        
        AppLog.d(TAG, "未检测到U盘");
        return null;
    }
    
    /**
     * 方法1：读取 /proc/mounts 查找 SD 卡
     * 这是最可靠的方法，能看到系统实际挂载的所有存储设备
     * 只接受 /storage/XXXX-XXXX 格式
     */
    /**
     * 从 /proc/mounts 列出<b>所有</b> /storage/XXXX-XXXX 挂载点。
     *
     * <p>{@link #getSdCardFromMounts()} 只返回第一个，用于「有没有U盘」的判断；
     * 卷选择器需要全部。</p>
     */
    private static java.util.List<File> listSdCardRootsFromMounts() {
        java.util.List<File> roots = new java.util.ArrayList<>();
        java.io.BufferedReader reader = null;
        try {
            reader = new java.io.BufferedReader(new java.io.FileReader("/proc/mounts"));
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split("\\s+");
                if (parts.length < 2) {
                    continue;
                }
                String mountPoint = parts[1];
                if (!mountPoint.matches("/storage/[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}")) {
                    continue;
                }
                File dir = new File(mountPoint);
                if (dir.exists() && dir.isDirectory() && dir.canRead()) {
                    roots.add(dir);
                }
            }
        } catch (Exception e) {
            AppLog.d(TAG, "读取 /proc/mounts 失败: " + e.getMessage());
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (Exception ignored) {
                    // 关闭失败无所谓
                }
            }
        }
        return roots;
    }

    private static File getSdCardFromMounts() {
        try {
            java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.FileReader("/proc/mounts"));
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split("\\s+");
                if (parts.length < 2) continue;
                
                String mountPoint = parts[1];
                // 只接受 /storage/XXXX-XXXX 格式
                if (mountPoint.matches("/storage/[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}")) {
                    File sdCard = new File(mountPoint);
                    if (sdCard.exists() && sdCard.isDirectory() && sdCard.canRead()) {
                        AppLog.d(TAG, "通过 /proc/mounts 找到U盘: " + mountPoint);
                        reader.close();
                        return sdCard;
                    }
                }
            }
            reader.close();
        } catch (Exception e) {
            // 忽略错误
        }
        return null;
    }
    
    /**
     * 方法2：通过标准 API getExternalFilesDirs 获取 SD 卡
     * 只接受 /storage/XXXX-XXXX 格式的路径
     */
    private static File getSdCardFromExternalFilesDirs(Context context) {
        try {
            File[] externalDirs = context.getExternalFilesDirs(null);
            
            if (externalDirs == null || externalDirs.length < 2) {
                return null;
            }
            
            // 第一个是内部存储，第二个及以后可能是U盘
            for (int i = 1; i < externalDirs.length; i++) {
                File dir = externalDirs[i];
                if (dir != null && dir.exists()) {
                    String path = dir.getAbsolutePath();
                    int index = path.indexOf("/Android/data/");
                    if (index > 0) {
                        String sdRootPath = path.substring(0, index);
                        // 只接受 /storage/XXXX-XXXX 格式
                        if (sdRootPath.matches("/storage/[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}")) {
                            File sdRoot = new File(sdRootPath);
                            if (sdRoot.exists() && sdRoot.canRead()) {
                                AppLog.d(TAG, "通过 getExternalFilesDirs 找到U盘: " + sdRoot.getAbsolutePath());
                                return sdRoot;
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            // 忽略错误
        }
        return null;
    }
    
    
    
    /**
     * 获取存储空间信息
     * @param path 存储路径
     * @return 可用空间（字节），如果获取失败返回 -1
     */
    public static long getAvailableSpace(File path) {
        if (path == null || !path.exists()) {
            return -1;
        }
        
        try {
            StatFs stat = new StatFs(path.getAbsolutePath());
            return stat.getAvailableBlocksLong() * stat.getBlockSizeLong();
        } catch (Exception e) {
            AppLog.e(TAG, "获取存储空间信息失败", e);
            return -1;
        }
    }
    
    /**
     * 格式化存储大小显示
     * @param bytes 字节数
     * @return 格式化后的字符串（如 "1.5 GB"）
     */
    public static String formatSize(long bytes) {
        if (bytes < 0) {
            return "—";
        }
        
        final long KB = 1024;
        final long MB = KB * 1024;
        final long GB = MB * 1024;
        
        if (bytes >= GB) {
            return String.format("%.1f GB", (double) bytes / GB);
        } else if (bytes >= MB) {
            return String.format("%.1f MB", (double) bytes / MB);
        } else if (bytes >= KB) {
            return String.format("%.1f KB", (double) bytes / KB);
        } else {
            return bytes + " B";
        }
    }
    
    /**
     * 设置里「录像保存路径」那一行写的目录。
     *
     * <ul>
     *   <li>正在录：录制器实际写的那个（盘写不进、换过盘就是新盘）。中转写入时录像先在内部缓存里，
     *       写完才转存过去 —— 这一行写转存到的目录，不写缓存；</li>
     *   <li>没在录：按设置下一次开录会写的那个；</li>
     *   <li>此刻录不了（没有 U 盘、开发者选项没开）：null —— 那一行写「未检测到 U 盘」，
     *       不写一个永远不会写进去的内置存储路径。</li>
     * </ul>
     *
     * <p>会碰盘，不要在主线程调。</p>
     */
    public static File savedVideoDir(Context context, boolean recording) {
        File actual = lastRecordingDir;
        File relayCache = new File(context.getCacheDir(), FileTransferManager.TEMP_VIDEO_DIR);
        if (recording && actual != null
                && !actual.getAbsoluteFile().equals(relayCache.getAbsoluteFile())) {
            return actual;
        }
        if (!isRecordingStorageAvailable(context)) {
            return null;
        }
        return getVideoDir(context);
    }
}
