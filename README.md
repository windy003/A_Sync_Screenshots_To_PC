# 截图同步 (ScreenshotSync)

一个 Android App：填写 Win11 SMB 共享信息后开启**后台同步服务**，App 会常驻后台监视手机
「截图」相册，**一出现新截图就自动上传**到局域网内的 Win11 共享文件夹，**上传成功后删除本地截图**。
可随时**暂停 / 继续 / 停止**。技术栈：Kotlin + 传统 View（XML + ViewBinding），
SMB 用 [jcifs-ng](https://github.com/AgNO3/jcifs-ng)。

## 工程结构

```
app/src/main/
├── AndroidManifest.xml
├── java/com/example/screenshotsync/
│   ├── MainActivity.kt          // 界面：配置、开启/暂停/停止、申请权限、显示状态与日志
│   ├── SyncService.kt           // 前台服务：ContentObserver 监视 + 自动上传 + 删本地
│   ├── SyncState.kt             // 进程内共享状态（StateFlow），服务写、界面读
│   ├── BootReceiver.kt          // 开机自启恢复后台同步
│   ├── Prefs.kt                 // 本地保存连接信息 + 服务/暂停状态 + 已同步记录
│   ├── ScreenshotRepository.kt  // 用 MediaStore 读取「截图」相册 + 删除本地文件
│   └── SmbUploader.kt           // jcifs-ng 连接 + 上传
└── res/layout/activity_main.xml
```

## 如何构建运行

1. 用 **Android Studio**（建议 Koala 或更新）打开本目录（它会自动补全 gradle wrapper 并同步依赖）。
   - 命令行构建需先生成 wrapper：在本目录执行 `gradle wrapper`，再 `./gradlew assembleDebug`。
2. 手机开启「USB 调试」连上电脑，点 Run。
3. App 内填写：
   - **服务器 IP**：Win11 的局域网 IP，如 `192.168.1.50`
   - **共享路径**：`共享名/子目录`，如 `MyShare/Screenshots`（不要带 `\\` 或 `smb://`）
   - **用户名 / 密码**：Win11 上有写入权限的账户
   - **域**：一般留空；若不行可填工作组名或电脑名
4. 点「开启后台同步」，首次会跳转系统设置让你给本 App 开启「**所有文件访问**」权限（见下）。
   开启后服务常驻后台，截图一出现就自动上传并删除本地文件。可勾选「打开 App 时自动开启后台同步」。
5. 运行期间可点「暂停 / 继续」临时停止/恢复，点「停止」彻底关闭后台服务。通知栏也有同样的按钮。

## 实现要点

- **后台监视**：`SyncService` 是前台服务（`foregroundServiceType="dataSync"`，带常驻通知），
  注册 `ContentObserver` 监听 `MediaStore.Images`，截图目录一有变化就触发一次扫描上传。
  扫描用 `Mutex` 串行化、用 `rescanRequested` 合并抖动，避免一张截图触发多次重复上传。
- **上传后删本地**：上传成功（或服务器已存在同名同大小文件）后，调用 `ScreenshotRepository.deleteLocal`
  按文件路径直接删除并清理 `MediaStore` 记录。
- **删除权限**：要在后台**无人值守**删除截图，必须有「所有文件访问」(`MANAGE_EXTERNAL_STORAGE`)；
  否则 Android 11+ 每删一个文件都会弹系统确认框，无法自动化。Android 9 及以下用读写存储权限即可。
- **读取截图**：用 `MediaStore` 按相册名 `BUCKET_DISPLAY_NAME = "Screenshots"` 过滤，
  同时覆盖 `Pictures/Screenshots` 和 `DCIM/Screenshots`。
- **暂停/继续**：`SyncState.paused`（StateFlow）控制；暂停时跳过扫描、并在上传循环中检查及时中断，
  通知栏与界面按钮联动。状态持久化到 `Prefs`，开机后由 `BootReceiver` 恢复。
- **去重**：以 `文件名|大小` 为 key 本地记录已同步项；上传前还会检查远端是否已存在同名同大小文件。
- **线程**：所有 SMB / IO 操作都在 `Dispatchers.IO` 上执行。

## Win11 端准备（务必先做）

1. 右键目标文件夹 → 属性 → **共享 → 高级共享** → 勾选共享，**权限**给「更改」。
2. 同一文件夹 → **安全** 标签页，确认登录账户有「修改/写入」权限（共享权限与 NTFS 权限取交集）。
3. 控制面板 → 网络和共享中心 → **高级共享设置**：当前网络开启「网络发现」和「文件和打印机共享」。
4. 确认手机与电脑同一网络，且该网络为**专用网络**（公用网络下防火墙会挡 SMB）。
5. 用一个**有密码的账户**；不要依赖 Guest（Win11 默认禁用不安全 guest 登录）。
6. 建议在路由器给这台 PC 绑定**静态 IP**，否则 IP 变了就连不上。

> 自测：先在另一台设备的文件管理器里手动连 `\\IP\共享名` 并新建一个文件，能成功说明服务端 OK，
> 再排查 App 就简单多了。

## 已知限制 / 可改进

- 密码以明文存于 `SharedPreferences`（个人工具可接受）。需要更安全可换 `EncryptedSharedPreferences`。
- **删除不可逆**：上传成功即删本地原图，请先确认服务器侧写入正常再长期使用。
- 「所有文件访问」权限较敏感、且无法上架 Google Play（个人侧载工具可接受）。
- 部分国产 ROM 会限制后台/自启，需在系统设置里给本 App「允许后台运行」「自启动」「电池不优化」，
  否则息屏后服务可能被杀。
- 若遇到 SMB3 加密协商失败，可在 `app/build.gradle.kts` 加入 `org.bouncycastle:bcprov-jdk15to18` 依赖。
