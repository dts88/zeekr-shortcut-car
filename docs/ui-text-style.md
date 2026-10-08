# 界面文字规范

适用于界面上的全部文字：`values/`、`values-en/`、`values-ms/` 的 strings.xml，以及关于页（strings_about.xml）。
新写或修改任何界面文字之前，先查 §10 术语表；表里没有的概念，按本规范的语体和句式写，再补进表里。

---

## 1. 语体

目标是车机系统设置的口吻（参照极氪 OS、Android / iOS 系统设置）：简短、陈述、用标准术语、不带情绪。
但不是公文：不用「予以」「进行」「之」「该功能将会」，能用短句就不用长句。

1. **陈述句，省略主语。** 不用「你」；确实需要人称时用「您」。关于页车主自己的口吻（致谢）不在此列。
2. **不用口语助词和填充词：** 句末「了」、吧、呢、就（「就接着录」）、一下、那个、自己、照旧、再来、实打实地。
3. **不用比喻和内部行话：** 封口、拉回来、搬、抄、拿走、占着、黑匣子、槽位、lane；「合成流」只在技术处出现（视频流配置、诊断、开发者选项）。
4. **不加旁白。** 一条说明只回答三件事：做什么、什么条件、有什么后果。原因最多一句；不用「——」接补充。
5. **写准。** 数字、条件、例外照实写（「熄屏 10 秒后停止录制」，不写「熄屏后停止」）。界面文字必须与实际行为一致：功能改了，引用它的文字一起改。
6. **术语只用 §10 里的那一个。**

| 口语 | 规范 | | 口语 | 规范 |
|---|---|---|---|---|
| 睡 / 醒 / 不让车机睡 | 休眠 / 唤醒 / 阻止休眠 | | 那一路 / 这一路 / 换一路 | 该路 / 此路画面 / 切换画面 |
| 打不开 / 放不出来 | 无法打开 / 无法播放 | | 这一刻 / 那一刻 | 此时 / 当前时刻 |
| 写不进 / 读不到 | 无法写入 / 无法读取 | | 关掉 / 关上 / 开着 | 关闭 / 开启 |
| 拿走了 / 占着 | 占用 | | 锁上 | 锁定 |
| 搬 / 搬过去 | 移动 / 转存 | | 抄（参数） | 沿用 |
| 修不了 / 好了 | 无法修复 / 已完成 | | 差一点点 | 略有偏差 |
| 没能 / 没拍到 | 未能 / 拍照失败 | | 还是没能 | 仍未 |
| 看不懂（响应） | 无法解析 | | 建不了 | 无法创建 |
| 不见了 | 找不到 / 已丢失 | | 密码不对 | 密码错误 |
| 人停的 | 手动停止的 | | 盖过 | 覆盖 |
| 先……再来 | 请先……后重试 | | 划（左右划） | 滑动（左右滑动） |
| 点一下 / 再点一次 | 点击 / 再次点击 | | 平常 / 跟着闪 | 正常 / 随闪烁 |

---

## 2. 各类文字的句式

| 类型 | 中文句式 | 示例 |
|---|---|---|
| 分区 / 页面标题 | 名词，2–6 字；页面标题与入口同名 | 存储、录像回放 |
| 设置项标题 | 功能名（名词短语），动作行用动宾短语；开关标题写功能，不写「开启 X」；尽量 ≤ 10 字；不带标点 | 锁定影像、重置窗口与画面、编辑视频流配置 |
| 试验性功能 | 标题后加「（试验性）」 | 车辆状态（试验性） |
| 设置项说明 | 写开启时的效果，动词开头；依赖写「需开启『X』」；关闭的后果写「关闭后，……」；需重启写在句末「重启应用后生效」；最多两句 | 在录像画面下方附加一栏车辆信号 |
| 状态 / 值 | 「名称：值」；值用「已开启 / 未开启 / 读取中… / 无法读取」 | 哨兵模式：已开启 |
| 值标签 | 1–3 字的状态词，不用「着」 | 开 / 关、有人 / 无人 |
| Toast · 成功 | 已 + 动词 + 对象 | 已锁定 3 个文件 |
| Toast · 失败 | 「无法 + 动作」或「X 失败：原因」；需要用户做事再接「，请重试」/「，请先……」 | 无法开始录制，请重试 |
| Toast · 进行中 | 正在 + 动词 + 「…」 | 正在扫描录像… |
| 对话框标题 | 确认类用问句；告知类用名词短语 | 删除所选录像？ / 未检测到 U 盘 |
| 对话框正文 | 先说后果，再说要做什么；1–3 句；每句以「。」结尾 | 删除后无法恢复。 |
| 按钮 | 动词或动宾，2–6 字；破坏性操作的确认按钮复述动作（删除、关闭），不用「确定」；纯告知用「知道了」；取消用「取消」 | 仍然录制、下载并安装 |
| 通知 | 标题写状态，正文写可做的动作 | 正在录制 / 点击返回应用 |
| 无障碍描述 | 按钮的作用，动宾短语 | 返回主界面 |
| 空状态 / 占位 | 暂无 + 对象；未 + 动词 | 暂无照片、未检测到 U 盘 |

---

## 3. 标点

**只有一句话的不加句末「。」；出现两句及以上的，每一句都以「。」结尾，包括最后一句。** 对话框正文永远是完整句子，以「。」结尾。

- 标题、按钮、标签、值：不加句末标点。
- 用「；」连接的并列分句算一句。句内停顿用「，」。
- 中文用全角标点：，。：；？！（）「」，嵌套引号用『』。英文、数字、路径内部用半角。
- 「」只用来引用界面上的名称（设置项、按钮、页面）和界面上显示的值；不用 “ ”。
- 省略号用一个「…」（U+2026），只表示进行中；不用「...」「……」。
- 不用破折号「——」（关于页车主的致谢除外）。
- 「：」用于「名称：值」「X 失败：原因」，冒号后不加空格。
- 「 · 」（两侧各一个空格）只用在紧凑的状态行里分隔数值（「3 段 · 1.2 GB」）；「 / 」用于并列选项（「开始 / 停止录制」）。
- 问号只用于确认类对话框标题；不用感叹号。

**英文**：单句片段不加句号，多句时每句都加；「，请重试」对应「. Try again」，整条按一句处理，末尾不加句号（Can't start recording. Try again）。不用 em dash，改用句号或冒号；界面名称不加引号；单引号在资源里写 `\'`。
**马来文**：同英文（「. Cuba lagi」同上）。

---

## 4. 数字与单位

- 一律阿拉伯数字（「最多 10 位」，不写「十位」）。
- 中文与英文、数字之间加一个半角空格：U 盘、GPU 逐像素、Beta 版、第 3 段。品牌型号照官方写法「极氪7X」。「」和括号的内侧不加空格。
- 数字与单位之间加空格：10 秒、3 分钟、1.5 GB、20 fps；百分号、度数、乘号不加：80%、140°、1280×800（用「×」，不用字母 x）。
- 中文界面用中文时间单位（秒、分钟、小时）；存储和码率用 GB / MB / Mbps；帧率用 fps。英文只在紧凑标签里用 s / min / h，句子里写全 seconds / minutes / hours。
- 量词：个文件、张照片、组照片、段（片段）、路（摄像头）、项（多选计数）。
- 英文复数用 Android `<plurals>`，不写 clip(s)、file(s)。

---

## 5. 引用其他设置和界面元素

- 路径从「设置」写起，整条放进一对「」，→ 两侧各一个半角空格：「设置 → 存储 → 存储位置」。
- 单个元素：点击「开始录制」。
- 名称与界面标题逐字一致；可以省略的只有三种：「（试验性）」后缀、双语标题的另一半（「语言 / Language」引用为「语言」）、选项名后面括号里的解释（「宽视野（地平线弯曲）」引用为「宽视野」）。
- 不写「在设置里」而不给路径。
- 改名时必须搜一遍引用它的文字，包括其他字符串、代码注释和文档，一起改。
- 英文、马来文不加引号，名称与界面一致：Settings → Storage → Storage location；Tap Start recording；Tetapan → Storan → Lokasi storan。
- 关于页没有马来文，马来文界面显示英文；马来文文字引用关于页里的条目时写英文名（如 Safety notice）。

---

## 6. 固定说法

| 中文 | English | Bahasa Melayu |
|---|---|---|
| 重启应用后生效 | Takes effect after restarting the app | Berkuat kuasa selepas apl dimulakan semula |
| 请重试 | Try again | Cuba lagi |
| 无法（动作） | Can't (action) | Tidak dapat (…) |
| （X）失败：（原因） | (X) failed: (reason) | (X) gagal: (sebab) |
| 正在（动作）… | (Action)ing… | (kata kerja me- / ber-)…，如 Mengimbas… |
| 已开启 / 已关闭 | On / Off | Dihidupkan / Dimatikan |
| 需开启「X」 | Requires X | Memerlukan X |
| 关闭后，…… | When off, … | Jika dimatikan, … |
| 需开启开发者选项 | Requires developer options | Memerlukan pilihan pembangun |
| 未检测到 U 盘 | No USB drive found | Tiada pemacu USB ditemui |
| 暂无（对象） | No (objects) yet | Belum ada (…) |
| 删除后无法恢复。 | This can't be undone. | Tindakan ini tidak boleh dibatalkan. |
| 原因：无网络连接 / 连接超时 / 存储空间不足 / 文件不存在 | no network connection / connection timed out / not enough storage space / file not found | tiada sambungan rangkaian / sambungan tamat masa / ruang storan tidak cukup / fail tidak ditemui |

失败提示不显示异常的原文（英文类名、`null`）：能归到上面几种原因的就写原因，归不进去的只写「无法……」，异常本身只进日志。

英文用缩写 can't / couldn't / isn't，不写 cannot / could not。

---

## 7. 英文与马来文

**英文**
- 平实、简短的界面英文；句首大写，其余小写（sentence case）。
- 功能名当专名，句中也大写首字母：Super mirror、Lock footage、Sentry Mode、Driving info bar。
- 说明用第三人称动词开头（Shows…、Adds…、Keeps…）；避免 you，确需时可用。
- 车机 = head unit，不写 car。
- 和中文说同一件事：可以比中文短，但不能少条件，也不能多出中文没有的信息。

**马来文**
- 与中文同一张术语表；开关用 hidupkan / matikan，窗口和页面用 buka / tutup；回放用 main semula；环视用 sekeliling；车机用 unit kepala。
- 不留未翻译的英文词；例外：贴边标签 Super mirror（三种语言都用英文，有意为之）、Auto hold 这类车上本来就印着英文的标识。

---

## 8. 改写示例

| 改写前 | 改写后 |
|---|---|
| 保活 / 被杀了、车机重启了，尽量把自己拉回来。关掉就不回来了 | 开机自启动 / 保持应用在后台运行。车机重启或应用被系统关闭后自动重新启动，并恢复已开启的超级后视镜、悬浮按钮和自动录制。关闭后不再自动启动。 |
| 熄屏录制 / 熄屏时在录像，就接着录，并且不让车机睡，最长按下面设的时长 | 熄屏录制（阻止休眠） / 熄屏时若正在录制，继续录制并阻止车机休眠，最长为下方设定的时长 |
| 熄屏后不让车机睡，最多（小时） | 阻止休眠时长上限（小时） |
| 录像被打断：%1$s。等环视画面恢复后自动继续 | 录制中断：%1$s。环视画面正常后自动恢复录制。 |
| 相机被别的程序拿走了 | 摄像头被占用 |
| 这一刻没有这一路的录像 / 这一段放不出来 | 该路此时无录像 / 此片段无法播放 |
| 把这份配置改回新建时的样子，你在这里改的会丢掉。 | 将此配置恢复为默认值，此处的修改将丢失。 |
| 保存完成前请不要关闭本窗口 —— 关掉就断开了 | 保存完成前请勿关闭此窗口，关闭后连接将断开 |
| 用相机自己的 JPEG 输出拍照，尺寸取相机最大；关掉则是抓预览画面，分辨率跟着预览走。改完需重启应用 | 使用摄像头的 JPEG 输出，按最大尺寸拍照；关闭后从预览画面截取，分辨率与预览相同。重启应用后生效。 |
| 看不懂 GitHub 的响应 / 密码不对 | 无法解析 GitHub 的响应 / 密码错误 |

英文：
- Brings the app back after it is killed or the car restarts. Off: it stays gone → Keeps the app running in the background and restarts it after it is closed or the head unit restarts. When off, it isn't restarted.
- Keep the car awake for up to (hours) → Keep awake for up to (hours)
- Could not start recording — try again → Can't start recording. Try again

---

## 9. 车主定过的名称与文字（不改）

- 功能名：超级后视镜 / Super mirror（「电子后视镜」只作解释）；锁定影像 / Lock footage；熄屏持续录制；行驶信息条；车辆状态（试验性）；系统信息（试验性）；鱼眼校正按钮的英文 Straighten；按钮透明度；关于与致谢。
- 文字：set_screen_off_keep_summary；record_screen_off_continue / _pause / _stop / _needs_sentry；dlg_footage_lock_off_msg；set_footage_lock_*；msg_storage_locked_*；关于页的致谢与来源（about_thanks_*、about_credits_*）。
- vi_* 信号名来自实车实测的结论：措辞可以润色，含义必须一字不差。
- 车主定过的文字即使与本规范有出入（如多句不以「。」结尾、个别口语），也不主动改。

---

## 10. 术语表

每个概念只用一个叫法。「例外」列之外，不用其他说法。

| 概念 | 中文 | English | Bahasa Melayu | 例外与说明 |
|---|---|---|---|---|
| 录制（动作） | 录制：开始录制、停止录制、录制中、录制中断 | record / recording | rakam / merakam / rakaman | 「录像」不作动词；不用单字「录」 |
| 录像（保存的文件） | 录像；录像存储上限 | recording(s)；与照片并列时 video(s)；Video storage cap | rakaman；与照片并列时 video；Had storan video | 「视频」只用于「视频流」「视频流配置」，以及转述手机浏览器的叫法（「图片或视频」） |
| 片段与一次录像 | 片段（量词「段」：每段 3 分钟、第 2 段）；一次从开始到停止的录制 = 录像；回放里切换用「上一条 / 下一条」 | clip；recording；Previous / Next | klip；rakaman；Sebelumnya / Seterusnya | — |
| 丢帧 | 丢帧（录像里少了画面）；U 盘写入速度跟不上 | dropped frames、drop frames；writes too slowly | bingkai tercicir；menulis terlalu perlahan | 不用「掉帧」「卡帧」 |
| 影像 | 影像（录像和照片的合称） | footage | rakaman | 单指一种时写录像或照片；「倒车影像」「泊车影像」是原厂功能名 |
| 照片 | 照片（量词「张」「组」）；照片存储上限 | photo(s)；Photo storage cap | foto；Had storan foto | 「图片」只指非照片的图像（使用指南插图） |
| 回放 | 回放；页面名：录像回放、照片回放 | playback；页面名 Videos、Photos | main semula；页面名 Video、Foto | 不用「回看」 |
| U 盘 | U 盘；需区分类型时写「U 盘或固态硬盘」 | USB drive | pemacu USB | 不用「录像盘」「固态盘」 |
| 存储位置 / 录像保存路径 | 存储位置（设置中选定的 U 盘）；录像保存路径（实际写入的目录） | Storage location；Video folder | Lokasi storan；Folder video | — |
| 内置存储 | 内置存储 | internal storage | storan dalaman | 解释寿命时写「内置存储的写入寿命有限」；不用「闪存」「车机存储」「内部存储」作名称 |
| 环视 | 环视；技术处：环视合成流；版式：四格显示 / 整幅显示 | surround view（标签 Surround）；composite stream；2×2 grid / full frame | paparan sekeliling（标签 Sekeliling）；strim komposit；grid 2×2 / bingkai penuh | 马来文用 sekeliling，不用 keliling |
| 座舱 | 座舱；前座舱、后座舱 | cabin；front cabin、rear cabin | kabin；kabin depan、kabin belakang | 不用「车厢」；「前排 / 后排」只指座位排 |
| 摄像头 | 摄像头；摄像头映射 | camera；Camera mapping | kamera；Pemetaan kamera | Android 权限名照系统写「相机权限」；关于页致谢原文不动 |
| 路 / 画面 | 量词「路」（4 路、录制 3 路）；指某一路用「该路」「此路画面」；看到的内容叫「画面」 | camera；view | kamera；paparan | 不用「那一路」「换一路」；英文、马来文不用 lane / lorong（lorong 只指车道） |
| 视频流配置 | 视频流配置；入口「编辑视频流配置」；预设：极氪7X（环视）、极氪7X（环视 + 前后座舱） | Stream profile；Edit stream profile；Zeekr 7X (surround)、Zeekr 7X (surround + front and rear cabin) | Profil strim；Edit profil strim；Zeekr 7X (sekeliling)、Zeekr 7X (sekeliling + kabin depan dan belakang) | 同一预设在设置列表和状态条上同名 |
| 超级后视镜 | 超级后视镜；其窗口在本分区内称「窗口」 | Super mirror（句中也大写 S） | Cermin super | 「电子后视镜」只作解释；贴边标签三种语言都写 Super mirror |
| 悬浮按钮 | 悬浮按钮；按钮大小、按钮透明度、时长文字大小 | floating button；Button opacity | butang terapung；Kelegapan butang | 「悬浮窗」只用于系统权限名「悬浮窗权限」（Overlay / Paparan di atas apl lain） |
| 行驶信息条 | 行驶信息条；仅系统信息页每行下方的小字可简称「信息条」 | Driving info bar（行下小字 Info bar） | Bar maklumat pemanduan | — |
| 系统信息 | 系统信息（试验性） | System info (experimental) | Maklumat sistem (percubaan) | — |
| 车辆状态 / 车辆信号 | 车辆状态（试验性）只作主界面面板的功能名；泛指读到的数据用「车辆信号」 | Vehicle status (experimental)；vehicle signals | Status kenderaan (percubaan)；isyarat kenderaan | — |
| 车机 / 车辆 / 车 | 车机 = 运行本应用的中控主机；车辆 = 整车；单字「车」只在固定词里（下车、锁车、车门、车速、车牌、左舵车） | head unit；vehicle | unit kepala；kenderaan | 休眠、重启、占用摄像头的是车机；日常短语可用 car / kereta（leave the car） |
| 熄屏 / 休眠 | 熄屏、亮屏（屏幕）；休眠、唤醒（车机）；熄屏录制（阻止休眠）（开发者选项）；熄屏持续录制（系统） | screen off / on；sleep / wake；Screen-off recording (keep awake)；Keep recording when the screen goes off | skrin padam / hidup；tidur / bangun；Rakaman skrin padam (kekal berjaga)；Terus merakam apabila skrin padam | 不用「睡」「不让车机睡」；马来文不用 skrin mati |
| 主界面 | 主界面；返回主界面 | Main screen；Back to main screen | Skrin utama；Kembali ke skrin utama | 不用「录制界面」 |
| 应用 | 应用；需区分时「本应用」「其他应用」 | app；this app | apl；apl ini | 不用「软件」「程序」（关于页致谢原文除外） |
| 开发者选项 | 开发者选项；条件写「需开启开发者选项」 | developer options | pilihan pembangun | 不用「开发者模式」 |
| 锁定 / 解锁 | 锁定、解锁、已锁定；「受保护」只描述锁定的效果 | lock、unlock、locked；protected | kunci、buka kunci、dikunci；dilindungi | 悬浮按钮的「锁定位置」是另一个概念 |
| 开关与窗口 | 开关：开启 / 关闭（已开启、未开启、需开启「X」）；窗口、页面、文件、应用：打开 / 关闭；紧凑值标签：开 / 关 | turn on / off、On / Off；open / close | hidupkan / matikan、Hidup / Mati；buka / tutup | 马来文开关不用 buka / tutup |
| 手势 | 点击、长按、拖动、滑动（上下滑动 / 左右滑动） | tap、long press、drag、swipe | ketik、tekan lama、seret、leret | 不用「点一下」「划」「单击」 |
| 诊断信息 / 诊断报告 | 诊断信息（设置项与页面）；诊断报告（导出的文件） | Diagnostics；Diagnostics report | Maklumat diagnostik；Laporan diagnostik | — |
| 引用写法 | 「设置 → 系统 → 诊断信息」 | Settings → System → Diagnostics | Tetapan → Sistem → Maklumat diagnostik | 见 §5 |
| 开机自启动 | 开机自启动（2.10.4 起含保活，不再有「保持后台运行」） | Start on boot | Mula semasa but | 不用「保活」 |
| 水印 | 时间水印、应用水印、车牌号水印、水印显示录制规格 | stamp：Timestamp、App stamp、Plate number stamp、Include recording specs | tera：Tera masa、Tera nama apl、Tera nombor plat、Sertakan spesifikasi rakaman | 不用「角标」；英文不用 overlay、badge |
| 试验性 | 功能名后缀「（试验性）」 | (experimental) | (percubaan) | 不用「实验性」「试验功能」 |
| 鱼眼校正 | 鱼眼校正（按钮与设置项同名）；鱼眼校正方式：直线、宽视野（地平线弯曲）、全圆（直线略弯）；视野角度（超级后视镜）、校正视野（界面） | 按钮 Straighten；Fisheye correction、Fisheye projection；Straight lines、Wide (curved horizon)、Full circle (lines slightly curved) | 按钮 Luruskan；Pembetulan mata ikan、Unjuran mata ikan | 英文引用按钮时写 Straighten |
| 闪远光 / 双闪 | 闪远光；双闪 | flash-to-pass；hazard lights（值标签 Hazard） | kelip lampu tinggi；lampu kecemasan | — |
| 原厂 | 原厂（原厂 360、原厂界面、原厂行车记录仪） | factory | kilang | 英文不用 stock |
| 挡位 | 挡位 | gear | gear | 不写「档位」；「码率档」的「档」指等级，用字正确 |
| 驾驶座 / 操作按钮 | 驾驶座（一侧）；操作按钮、操作按钮位置 | driver's side；action buttons、Action button side | sebelah pemandu；butang tindakan、Sebelah butang tindakan | 不用「驾驶位」 |
| 自动清理 / 删除 | 自动清理（超出上限或空间不足时应用删除最旧的文件）；删除（用户手动） | automatic cleanup；delete | pembersihan automatik；padam | — |
| 开机 / 启动 | 开机 = 车机开机（开机自启动）；启动 = 应用启动（启动自动录制） | boot（Start on boot）；launch / open（Record on launch） | but（Mula semasa but）；buka（Rakam semasa dibuka） | — |
| 需重启生效 | 重启应用后生效（放在句末） | Takes effect after restarting the app | Berkuat kuasa selepas apl dimulakan semula | — |
| 确认按钮 | 知道了 | Got it | Faham | 不用「我知道了」 |
| 画面调节 | 画面调节；画面调节窗口 | Image adjustment | Pelarasan imej | 不用「亮度 / 降噪调节」 |
| 设置分区与页面 | 录制、存储、超级后视镜、悬浮按钮、界面、系统、开发者选项、检查更新、关于与致谢 | Recording、Storage、Super mirror、Floating button、Interface、System、Developer options、Check for updates、About & credits | Rakaman、Storan、Cermin super、Butang terapung、Antara muka、Sistem、Pilihan pembangun、Semak kemas kini、Perihal & penghargaan | 关于页没有马来文，见 §5 |
