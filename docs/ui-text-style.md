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
| 设置项说明 | 写开启时的效果，动词开头；依赖写「需开启『X』」；关闭的后果写「关闭后，……」；需重启写在句末「重启应用后生效」；最多两句 | 在环视录像画面下方附加一栏车辆信号 |
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
| 保活 / 被杀了、车机重启了，尽量把自己拉回来。关掉就不回来了 | 开机自启动 / 保持应用在后台运行。车机重启或应用被系统关闭后自动重新启动，并恢复已开启的超级镜子、盲区画面、悬浮按钮和自动录制。关闭后不再自动启动。 |
| 熄屏录制 / 熄屏时在录像，就接着录，并且不让车机睡，最长按下面设的时长 | 熄屏录制（阻止休眠） / 熄屏时若正在录制，继续录制并阻止车机休眠，最长为下方设定的时长 |
| 熄屏后不让车机睡，最多（小时） | 阻止休眠时长上限（小时） |
| 录像被打断：%1$s。等环视画面恢复后自动继续 | 录制中断：%1$s，正在自动恢复…（2.11 起接回不等环视，后半句跟着改；当时改成的是「环视画面正常后自动恢复录制。」） |
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

- 功能名：超级镜子 / Super mirror（2026-10-16 由「超级后视镜」改名，英文、马来文不变；「电子后视镜」只作解释）；锁定影像 / Lock footage；熄屏持续录制；行驶信息条；车辆状态（试验性）；系统信息（试验性）；鱼眼校正按钮的英文 Straighten；按钮透明度；关于与致谢。
- 文字：record_screen_off_continue（2026-10-12 批准「熄屏后继续录制环视」）/ _stop；dlg_footage_lock_off_msg；set_footage_lock_*；msg_storage_locked_*；关于页的致谢与来源（about_thanks_*、about_credits_*）。
- 2026-10-13 批准（2.11.2，熄屏 v3）：record_screen_off_pause「熄屏暂停，亮屏后恢复」（原来是「…唤醒后恢复」）；set_screen_off_keep_summary「熄屏后只录制环视，亮屏后前后座舱恢复录制。未开启哨兵模式时，车机休眠期间录制暂停，唤醒后自动恢复。关闭本项，熄屏后停止录制。」；set_screen_off_summary（开发者「熄屏录制（阻止休眠）」）「熄屏时若正在录制，继续录制环视并阻止车机休眠，最长为下方设定的时长」；vi_screen_power_event「中控屏」和它的值 vi_v_screen_off / _on（熄屏 / 亮屏）。
- record_screen_off_needs_sentry（「熄屏续录需开启哨兵模式」）2.11.2 删了：熄屏那一下接着录不再要哨兵模式；车机休眠时也接着录（哨兵模式开着等）才写 record_screen_off_continue，休眠时会停的写 record_screen_off_pause（项目所有者 2026-10-13 定，不另加句子）。
- set_screen_off_keep_summary 不写「黑满 30 秒再放」（2.11.3）：项目所有者 2026-10-13 定 —— 那 30 秒是为了软件稳定性，不是功能；说明以简单易懂为主，照原文「熄屏后只录制环视，亮屏后前后座舱恢复录制。…」。
- 2026-10-16 批准（2.12.0，PR #17 超级镜子方向标签）：set_rearview_camera_label_title「方向标签」/ Direction label / Label arah；set_rearview_camera_label_summary「在窗口左上角显示当前画面的方向」；set_rearview_reset_summary 加上「方向标签」。
- 2026-10-16 批准（关于页「开源与来源」，代码贡献）：about_credits_contributors「这些朋友为本应用贡献了代码：• wysie（GitHub）—— 大量设计以及代码。• FerixzTanz（GitHub）—— 打转向灯时显示侧方摄像头画面的最初代码和实车研究。」；英文 These friends contributed code: • wysie (GitHub) — a great deal of design and code. • FerixzTanz (GitHub) — the original code and on-car research for showing the side camera when the turn signal is on.
- 2026-10-17 批准（PR #41 盲区画面）：功能名 set_side_view_title「盲区画面」/ Blind spot view / Paparan titik buta；
  set_side_view_summary「打转向灯时在屏幕该侧显示侧方摄像头画面，转向灯关闭 1 秒后消失；倒挡或原厂画面显示时不显示。挂 D 挡时环视保持开启。」；
  set_side_view_min_speed_title「最低车速」、_summary「低于此车速时不显示；0 为不限」；side_view_close（关闭按钮的无障碍描述）「关闭盲区画面」；
  msg_side_view_unavailable「无法显示盲区画面」；开发者选项 dev_side_view_back / _up / _fov 的标题「盲区画面朝后角度」「盲区画面朝外角度」「盲区画面视野角度」和说明；
  列举悬浮窗功能处加上「盲区画面」的 set_auto_start_summary、dev_perm_overlay_why。
- 2026-10-19 批准（PR #61 悬浮按钮「锁定当前录像」）：选项 floating_action_lock_footage「锁定当前录像」/ Lock the current recording / Kunci rakaman semasa；
  锁上时 msg_footage_locked_around「已锁定前后 10 秒所在的录像片段」/ Locked the clips covering 10 seconds before and after /
  Klip yang meliputi 10 saat sebelum dan selepas telah dikunci；「锁定影像」关着时 msg_footage_lock_needs_setting
  「无法锁定，请先开启「设置 → 存储 → 锁定影像」」/ Can't lock. Turn on Settings → Storage → Lock footage first /
  Tidak dapat mengunci. Hidupkan Tetapan → Storan → Kunci rakaman dahulu。没在录时沿用 msg_footage_nothing_here「当前时刻无可锁定的录像」。
- 2026-10-19 批准（PR #38 下载更新时校验安装包）：upd_err_integrity，下载更新时安装包的 sha256 或大小与 GitHub 给的对不上、或 GitHub 没给 sha256，显示为「下载失败：安装包校验未通过，已中止」；English「Download failed: installer file failed verification, aborted」；Bahasa Melayu「Muat turun gagal: fail pemasang gagal pengesahan, dibatalkan」。
- 2026-10-19 批准（设置页分组）：组名 set_group_stream「视频流」、_auto_record「自动录制」、_photo「拍照」、_stamps「水印」、
  _cleanup「自动清理」、_views「切换画面」、_window「窗口」、_behavior「行为」、_appearance「外观」、_position「位置」、
  _main_screen「主界面」、_cameras「摄像头」、_files「文件维护」；另有几组直接用同名设置项的文字（存储位置、锁定影像、行驶信息条、
  鱼眼校正、盲区画面、录制）。英文、马来文照术语表。
- 2026-10-19 批准（超级镜子切换范围，取代「只显示前后视」开关）：set_rearview_views_title「切换范围」/ Views / Paparan；
  三档 opt_views_four「四向」/ All four / Empat arah、opt_views_front_rear「前后」/ Front and rear / Depan dan belakang、
  opt_views_rear_cabin「后视 + 后座舱」/ Rear + rear cabin / Belakang + kabin belakang；后座舱的方向标签沿用 slot_cabin_rear「后座舱」；
  set_rearview_reset_summary 里「只显示前后视」换成「切换范围」。
- 2026-10-20 批准（超级镜子说明跟上后座舱）：set_rearview_summary「悬浮显示环视中的任一路画面或后座舱画面；后视和后座舱画面左右镜像，与车内后视镜一致」；
  Shows any surround camera or the rear cabin in a floating window; the rear view and the rear cabin are mirrored left to right, like the interior mirror；
  Menunjukkan mana-mana kamera sekeliling atau kabin belakang dalam tetingkap terapung; paparan belakang dan kabin belakang dicerminkan kiri-kanan, seperti cermin pandang belakang dalaman。
- 2026-10-20 批准：set_rearview_button_mode_summary「点击上、下、左、右区域分别切换到前、后、左、右画面；「切换范围」为「前后」或「后视 + 后座舱」时只分上下两半，下半为后视」；
  …; with Views set to Front and rear or Rear + rear cabin, only the top and bottom halves are used, with the rear view at the bottom；
  …; jika Paparan ialah Depan dan belakang atau Belakang + kabin belakang, hanya separuh atas dan bawah digunakan, dengan paparan belakang di bawah。
- 2026-10-20 批准（导出、导入设置）：set_settings_export_title「导出设置」/ Export settings / Eksport tetapan；
  set_settings_export_summary「将当前设置保存为文件，与诊断报告存放在同一文件夹」；set_settings_import_title「导入设置」/ Import settings / Import tetapan；
  set_settings_import_summary「从导出的设置文件恢复全部设置；只能导入 %1$d.x 版本导出的文件」；settings_exported「已导出到：\n%1$s」；
  settings_export_failed「无法导出设置」、settings_export_failed_reason「导出设置失败：%1$s」；settings_pick_title「选择设置文件」；
  settings_file_label「%1$s · 版本 %2$s」；settings_none_title「未找到设置文件」、settings_none_msg「导入时从此文件夹读取导出的设置文件：%1$s。」；
  settings_import_refused_title「无法导入」、settings_other_version_msg「此文件由版本 %1$s 导出。只能导入 %2$d.x 版本导出的设置文件。」；
  settings_unreadable「无法读取此设置文件」；settings_import_confirm_title「导入设置？」、settings_import_confirm_msg「当前设置将被覆盖。导入后应用将退出，重新打开后生效。」；
  settings_import_action「导入并退出」；settings_import_failed「无法导入设置」；settings_imported_reopen「已导入设置，重新打开应用后生效」。英文、马来文见 strings.xml。
- 2026-10-20（主界面预览停画遮盖，车主定「文字提示只显示一个」）：preview_paused「画面已停止」/ Picture stopped / Gambar terhenti，
  沿用已批准的 mirror_paused_tap 前半句（主界面点一下不是「恢复」，不带后半句）。
- 2026-10-20 批准（关于页「开源与来源」，着重感谢 FerixzTanz 的参数调整）：about_credits_contributors 里 FerixzTanz 那一条改为「• FerixzTanz（GitHub）—— 盲区画面的最初代码和实车研究。盲区画面默认的角度和窗口大小，是他在自己的 7X 上实车调出来的，非常感谢他细致的参数调整。」；
  English「• FerixzTanz (GitHub) — the original code and on-car research for Blind spot view. Its default angles and window size are the ones he tuned on his own 7X. Many thanks for his careful parameter tuning.」（关于页只有中英两份）。
- vi_* 信号名来自实车实测的结论：措辞可以润色，含义必须一字不差。
- 车主定过的文字即使与本规范有出入（如多句不以「。」结尾、个别口语），也不主动改。

### 待项目所有者确认

下面这几条**不在**上面「不改」的单子里：照草稿上了三种语言，项目所有者还没看。
可以照本规范改；项目所有者批了哪一条，就把它移回上面的单子。

- 开发者选项「录制声音（试验）」（2026-10-20，车主定先做框架、上车比来源）：dev_footage_audio_title「录制声音（试验）」/
  Record sound (experimental) / Rakam bunyi (percubaan)；dev_footage_audio_summary「环视录像带上一路车内麦克风的声音，打开时请求录音权限。
  录制模式为 MediaRecorder 时不带声音。麦克风的位置、熄屏和后台时能不能录到，还在实车测试」；dev_footage_audio_source_title「麦克风来源」，
  两档 dev_footage_audio_source_raw「原始（VOICE_RECOGNITION，不经处理，声音小）」、dev_footage_audio_source_call「通话（VOICE_COMMUNICATION，经降噪和回声消除）」；
  没给权限时 msg_footage_audio_no_permission「没有录音权限，录制声音没有打开」；权限设置页 dev_perm_microphone「麦克风权限」、
  dev_perm_microphone_why「开发者选项「录制声音」要用」（录制声音开着时才列）。英文、马来文见 strings.xml。
- 系统信息「驾驶模式」（2026-10-20，Lab 车上对过值，车主要求加）：vi_drive_mode「驾驶模式」/ Drive mode / Mod pemanduan；
  三个值照车机界面的叫法：vi_v_drive_comfort「舒适」/ Comfort / Selesa，vi_v_drive_standard「标准」/ Standard / Standard，
  vi_v_drive_sport「运动」/ Sport / Sukan。没对上的值照原样写号码（同灯光开关）。
- 开发者选项「模拟声浪（试验）」（2026-10-21，车主定先只做车内、只做运动模式）：分组 dev_drive_sound_group「模拟声浪」/ Drive sound /
  Bunyi pemanduan；开关 dev_drive_sound_title「模拟声浪（试验）」/ Simulated drive sound (experimental) / Bunyi pemanduan simulasi (percubaan)；
  dev_drive_sound_summary「运动模式下按油门、车速和加减速合成发动机声，从车内喇叭播放并跟随媒体音量。停车时切换到运动模式会先播放启动声；
  倒挡时音量降低 6 dB」；dev_drive_sound_volume_title「声浪音量」（选项直接写 dB 数：−18 / −12 / −6 / 0 dB）；
  dev_drive_sound_rpm_title「转速变化方式」，两档 dev_drive_sound_rpm_continuous「连续」、dev_drive_sound_rpm_shifts「模拟换挡」；
  悬浮按钮动作 floating_action_toggle_drive_sound「开启 / 关闭模拟声浪」，按下后 msg_drive_sound_on「已开启模拟声浪」/
  msg_drive_sound_off「已关闭模拟声浪」（开发者选项关着时沿用 msg_dev_only）。英文、马来文见 strings.xml。
- 系统信息「动力流」（2026-10-21，Lab 手册「一」观察一致，模拟声浪要用）：vi_power_flow「动力流」/ Power flow / Aliran kuasa；
  四个值 vi_v_power_drive「驱动」/ Drive / Pacuan，vi_v_power_regen「回收」/ Regen / Jana semula，vi_v_power_idle「空闲」/ Idle / Melahu，
  vi_v_power_not_ready「未就绪」/ Not ready / Belum sedia。没对上的值照原样写号码。

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
| 超级镜子 | 超级镜子（2.12 起，原「超级后视镜」）；其窗口在本分区内称「窗口」 | Super mirror（句中也大写 S） | Cermin super | 「电子后视镜」只作解释；贴边标签三种语言都写 Super mirror |
| 盲区画面 | 盲区画面（打转向灯时在屏幕该侧显示侧方摄像头画面的窗口，设置在「超级镜子」分区里） | Blind spot view（句中也大写 B） | Paparan titik buta | 2026-10-17 项目所有者批准；不用「盲区辅助」（原厂功能的名字）、「侧视弹窗」 |
| 转向灯 | 转向灯；打转向灯 | turn signal | lampu isyarat | 双闪见「闪远光 / 双闪」 |
| 方向标签 | 方向标签（超级镜子窗口左上角那块写着前、后、左、右的标签） | Direction label | Label arah | 2.12 加；标签上的字用「前 / 后 / 左 / 右」（zeekr_lane_*），后座舱写「后座舱」（slot_cabin_rear） |
| 切换范围 | 切换范围（超级镜子在哪几路之间切）：四向、前后、后视 + 后座舱 | Views：All four、Front and rear、Rear + rear cabin | Paparan：Empat arah、Depan dan belakang、Belakang + kabin belakang | 2026-10-19 加，取代「只显示前后视」 |
| 悬浮按钮 | 悬浮按钮；按钮大小、按钮透明度、时长文字大小 | floating button；Button opacity | butang terapung；Kelegapan butang | 「悬浮窗」只用于系统权限名「悬浮窗权限」（Overlay / Paparan di atas apl lain） |
| 行驶信息条 | 行驶信息条；仅系统信息页每行下方的小字可简称「信息条」 | Driving info bar（行下小字 Info bar） | Bar maklumat pemanduan | — |
| 系统信息 | 系统信息（试验性） | System info (experimental) | Maklumat sistem (percubaan) | — |
| 车辆状态 / 车辆信号 | 车辆状态（试验性）只作主界面面板的功能名；泛指读到的数据用「车辆信号」 | Vehicle status (experimental)；vehicle signals | Status kenderaan (percubaan)；isyarat kenderaan | — |
| 车机 / 车辆 / 车 | 车机 = 运行本应用的中控主机；车辆 = 整车；单字「车」只在固定词里（下车、锁车、车门、车速、车牌、左舵车） | head unit；vehicle | unit kepala；kenderaan | 休眠、重启、占用摄像头的是车机；日常短语可用 car / kereta（leave the car） |
| 熄屏 / 休眠 | 熄屏、亮屏（屏幕）；休眠、唤醒（车机）；熄屏录制（阻止休眠）（开发者选项）；熄屏持续录制（录制） | screen off / on；sleep / wake；Screen-off recording (keep awake)；Keep recording when the screen goes off | skrin padam / hidup；tidur / bangun；Rakaman skrin padam (kekal berjaga)；Terus merakam apabila skrin padam | 不用「睡」「不让车机睡」；马来文不用 skrin mati |
| 中控屏 | 中控屏（车机的那块屏幕；系统信息页屏幕电源事件 0x20290300 那一行就叫它，值写熄屏 / 亮屏） | Center screen | Skrin tengah | 2.11.2 加；只在要指那块屏幕本身时用，说状态仍用熄屏 / 亮屏 |
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
| 鱼眼校正 | 鱼眼校正（按钮与设置项同名）；鱼眼校正方式：直线、宽视野（地平线弯曲）、全圆（直线略弯）；视野角度（超级镜子）、校正视野（界面） | 按钮 Straighten；Fisheye correction、Fisheye projection；Straight lines、Wide (curved horizon)、Full circle (lines slightly curved) | 按钮 Luruskan；Pembetulan mata ikan、Unjuran mata ikan | 英文引用按钮时写 Straighten |
| 闪远光 / 双闪 | 闪远光；双闪 | flash-to-pass；hazard lights（值标签 Hazard） | kelip lampu tinggi；lampu kecemasan | — |
| 原厂 | 原厂（原厂 360、原厂界面、原厂行车记录仪） | factory | kilang | 英文不用 stock |
| 挡位 | 挡位 | gear | gear | 不写「档位」；「码率档」的「档」指等级，用字正确 |
| 驾驶座 / 操作按钮 | 驾驶座（一侧）；操作按钮、操作按钮位置 | driver's side；action buttons、Action button side | sebelah pemandu；butang tindakan、Sebelah butang tindakan | 不用「驾驶位」 |
| 自动清理 / 删除 | 自动清理（超出上限或空间不足时应用删除最旧的文件）；删除（用户手动） | automatic cleanup；delete | pembersihan automatik；padam | — |
| 开机 / 启动 | 开机 = 车机开机（开机自启动）；启动 = 应用启动（启动自动录制） | boot（Start on boot）；launch / open（Record on launch） | but（Mula semasa but）；buka（Rakam semasa dibuka） | — |
| 需重启生效 | 重启应用后生效（放在句末） | Takes effect after restarting the app | Berkuat kuasa selepas apl dimulakan semula | — |
| 确认按钮 | 知道了 | Got it | Faham | 不用「我知道了」 |
| 画面调节 | 画面调节；画面调节窗口 | Image adjustment | Pelarasan imej | 不用「亮度 / 降噪调节」 |
| 设置分区与页面 | 录制、存储、超级镜子、悬浮按钮、界面、系统、开发者选项、检查更新、关于与致谢 | Recording、Storage、Super mirror、Floating button、Interface、System、Developer options、Check for updates、About & credits | Rakaman、Storan、Cermin super、Butang terapung、Antara muka、Sistem、Pilihan pembangun、Semak kemas kini、Perihal & penghargaan | 关于页没有马来文，见 §5 |
