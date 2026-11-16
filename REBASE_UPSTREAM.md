# 上游更新指南（内核 rebase，主仓库 merge）

**内核用 rebase，主仓库用 merge。** 内核自有补丁少（chen 的适配补丁 + 我们 3 个 Smart fix），冲突面固定；
主仓库自有 commit 有 36 个，而上游这一个月把 UI、Android 架构和 Core 协议都重写了，逐提交 rebase 会把
同一批冲突重解几十遍，所以主仓库改成**一次 merge**。

最近一次实测：**2026-11-16**。上游 ref：`chen08209/FlClash` main `be847366`、
`vernesong/Alpha` `c4df732a`、`chen/Clash.Meta` `FlClash` `5b453f80`。

本次结果：

- 内核：chen 的适配补丁已经从 1 个长成 10 个提交，整体 cherry-pick 到 `c4df732a` 之上，仍是 3 个老冲突文件；
  我们的 3 个 Smart fix 零冲突。推成 `FlClash-smart-rebase` = `113bf520`。
- 主仓库：一次 merge，86 个文件冲突，其中 android 36 个全部是包名改名（`com.follow.clash` →
  `com.flsmart.clash`）撞上游重写的进程架构，用「取上游 + 脚本改名」批量解决。
- **删掉了 fork 的 profile-keys 特性**（见下方「与上游的设计冲突」），其余只保留 Smart 那一条线。
- `flutter analyze` 干净、`flutter test` 全绿；Android APK / 桌面端未构建（本机无 SDK/NDK）。

## 与上游的设计冲突（2026-11 起）

fork 曾用 10 个 commit 实现「**以配置文件为准**」：`getProfileKeys` core 方法、`useProfileSettings` 开关、
`ProfileOwnedConfig`、per-key 的 `appOwned()` 包装与相关测试。
上游在 `491fae10 feat(views)!: set DNS, NTP, sniffer and TUN key by key` 里给出相反答案：
**BREAKING CHANGE：DNS/NTP 覆盖开关被移除，选中的 key 应用到每个 profile**，也就是「以 App 设置为准」。

两者是同一问题的相反答案，同时存在会互相打架。**2026-11-16 起跟上游走**：删除 fork 的 profile-keys 整条线
（含 `test/common/task_profile_settings_test.dart`、`test/views/general_view_test.dart` 等），
旧实现留在 tag `app-backup-2026-11-16` 与 `pre-*` 历史里，需要时可取。

## 项目结构

```
主仓库（Flutter + Go 桥接）
├── origin:   你的 fork（如 Seameee/FlClashSmart）
├── upstream: chen08209/FlClash              ← 主仓库上游
├── satar07:  Satar07/FlClashSmart           ← 来源 fork（可选，用于对比）
│
├── lib/ … android/ …                      ← chen08209/FlClash + 36 个 fork commit
│
└── core/Clash.Meta（子模块）= 内核
    ├── origin:    你的内核 fork（如 Seameee/FlClashCore，分支 FlClash-smart-rebase）
    ├── vernesong: vernesong/mihomo Alpha    ← Smart 内核上游（LightGBM/leaves）
    └── chen:      chen08209/Clash.Meta      ← FlClash 适配补丁来源
```

**内核分支 = `vernesong/Alpha` + chen 的 FlClash 适配提交（cherry-pick，保留作者）+ 我们 3 个 Smart fix。**

关键点：**不再维护自己的适配补丁副本**。chen 的适配提交整块 cherry-pick 进来，冲突只发生在
vernesong 与 chen 都改过的少数文件（见下表，2026-11-16 实测仍是 3 个）。

## 前置

三个内核 remote 用 https（部分网络下 SSH 会被拦截）：

```bash
cd core/Clash.Meta
git remote set-url origin     https://github.com/<你>/FlClashCore.git
git remote set-url vernesong  https://github.com/vernesong/mihomo.git
git remote set-url chen       https://github.com/chen08209/Clash.Meta.git
```

## 一、内核子模块更新

先打备份 tag（回滚用），再按情况选 A 或 B。

```bash
cd core/Clash.Meta
git tag -f kernel-backup-$(date +%F) HEAD
git fetch vernesong Alpha
git fetch chen FlClash
```

**情况 A：只有 vernesong 前进，chen 未变**

```bash
# 把「chen 适配 + 我们的 fix」整体搬到新的 vernesong/Alpha 上
git rebase --onto vernesong/Alpha $(git rev-parse chen/FlClash) FlClash-smart-rebase
```

**情况 B：chen 也更新了**（chen 会把自己的适配提交 rebase 到新上游上，SHA 会变）

```bash
BACKUP=kernel-backup-$(date +%F)
git checkout -B FlClash-smart-rebase vernesong/Alpha
# chen 的适配提交有多个，按时间顺序整段 cherry-pick
git cherry-pick -x $(git rev-list --reverse $(git merge-base chen/FlClash vernesong/Alpha)..chen/FlClash)
git cherry-pick $(git rev-parse $BACKUP~2) $(git rev-parse $BACKUP~1) $(git rev-parse $BACKUP)  # 我们 3 个 Smart fix
```

### 内核已知冲突（2026-11-16 实测，仍只有这 3 个文件）

| 文件 | 冲突原因 | 处理 |
|------|---------|------|
| `component/updater/update_geo.go` | chen 加 `sendGeoUpdateStatus`/`Chtimes`；vernesong 用 `geodata.VerifyGeodataBytes` 取代 `geoLoader` | 保留 chen 的状态通知 + **保留 vernesong 的 `VerifyGeodataBytes`**；不要恢复 `geodata.GetGeoDataLoader("standard")` |
| `tunnel/statistic/manager.go` | chen 加 6 个 `proxy*` 计数器与 `direct` 标记；vernesong 加 `smartTarget` 与 bucketWindow | **并集**（两者都留） |
| `tunnel/statistic/tracker.go` | chen 把变量改名 `tt` 并加 `Push*(direct, …)`、`IsDirect`；vernesong 加 `trackerUUID`/`metadata.UUID` 与 `uploadBucketWindow` | 保留 UUID 与 bucketWindow 行 + 用 `tt` 命名 + 保留 `direct` |

处理原则：**保留 Smart 内核能力（LightGBM/leaves/smartTarget）+ 贴近 chen 的适配逻辑**，适配新的 mihomo API。
解决后 `gofmt`，并确认 `git grep -c '^<<<<<<<'` 为 0。

## 二、主仓库更新

```bash
git tag -f app-backup-$(date +%F) HEAD
git fetch upstream main
git log --oneline origin/smart --not HEAD   # 有输出 = 远端有我没有的 commit，先 cherry-pick 再动
git merge upstream/main
```

merge 会留下 `MERGE_HEAD` 与一次「Merge remote-tracking branch 'upstream/main'」。合并提交信息里写清楚
采用/放弃了哪些 fork 特性（例如 2026-11-16 那次写明「跟上游改用按键覆盖，删掉 profile-keys」）。

### 主仓库已知冲突（2026-11-16 实测 86 个文件）

| 区域 | 策略 |
|------|------|
| `android/**`（36 个） | **整棵树取上游**（`git rm -r -f android && git checkout upstream/main -- android`），再跑包名改名脚本。上游改成 gomobile 绑定 Core，旧的 `core.cpp` / `jni_helper.*` / `InvokeInterface.kt` 全部消失，不要试图手工 merge |
| 包名改名映射 | `com/follow/clash/core` → `com/flsmart/core`、`com/follow/clash/common` → `com/flsmart/common`、`com/follow/clash/service` → `com/flsmart/clash/service`、`com/follow/clash` → `com/flsmart/clash`（**长前缀先替换**）；路径与文本都要改，含 `namespace`/`applicationId`/JNI 符号/aidl |
| `android/app/google-services.json` | 上游删掉 Firebase：跟随删除，`Setup Android Signing` 里的 `SERVICE_JSON` guard 一并删 |
| `core/**`（Go 桥） | **整棵树取上游**（`core/hub.go` 已拆成 hooks/delay/connection/… 一堆文件），然后按下面「core 移植清单」补回 fork 的部分；`core/Clash.Meta` 的 gitlink 手工 `git add` 成新内核 HEAD |
| `core` 移植清单 | ① `proxy.go` 的 `isProxyGroupType` 加 `constant.Smart`；② `geo.go` 的 `geoResources` 加 `"MODEL"`；③ `go mod tidy` |
| `lib/core/{interface,method,controller}.dart` | 上游把 Core 协议整体改名（`core.init` / `config.setup` / …），**取上游**；fork 的 `getProfileKeys` 随 profile-keys 一起删 |
| `lib/common/task.dart` | 取上游，然后补两处：`rawConfig['geox-url']` 过滤掉 `GeoResource.MODEL`，另写 `rawConfig['lgbm-url']` |
| `lib/models/clash_config.dart` | 取上游，然后补：`defaultGeoXUrl` 的 `MODEL`、`ProxyGroup` 的 5 个 smart 字段与 `definition` 里的 smart 输出 |
| `lib/models/state.dart`、`lib/providers/state/*`、`lib/providers/actions/setup.dart`、`lib/common/preferences.dart` | 取上游（profile-keys 删掉）。`preferences.dart` 里补回 `getModelAssetHash`/`setModelAssetHash`，改用上游的 `_store.value` 形式 |
| `lib/database/{database,groups}.dart` | **取上游**。上游把 `proxyGroups` 的整排列换成单个 `definition` JSON 列（`_moveGroupOptionsToDefinition`，v18），smart 选项自动跟着 JSON 走，不再需要 5 个独立列 |
| 数据库版本 | `schemaVersion` 22 → **23**（跟上游）。fork 旧库（v11/v12）升上来时，上游 v17/v18 迁移会重建 profiles、把群组列折进 `definition`；**fork 那 5 个 smart 列里的旧值不会迁移**，升级后需要在编辑器里重设 |
| `lib/views/profiles/overwrite/custom/groups.dart` | 上游删了整页：跟随删除。smart 选项行因此在 UI 上暂时没有入口（见「Smart 验证清单」的已知缺口） |
| `lib/common/constant.dart` | 保留 `MODEL`/`modelAssetHashFile`/`modelAssetHashKey`；`repository` 用本 fork（`Seameee/FlClashSmart`），上游删掉的 `dialogCommonWidth`/`defaultExternalController` 不要留 |
| `linux/packaging/*/make_config.yaml` | `startup_wm_class` 用 **`com.flsmart.clash`**（上游新加的键，值必须与 `linux/CMakeLists.txt` 的 `APPLICATION_ID` 一致，`test/tool/packaging_config_test.dart` 会查） |
| `pubspec.yaml` | 版本号用上游 +1（`0.9.3+2026110401` → `0.9.4+2026111601`）；依赖用上游 |
| `.gitmodules` | 只留 `core/Clash.Meta`（我们的 fork）与 `plugins/prism_ui`（上游新插件，URL 改 https）；`plugins/flutter_distributor`（`setup.dart` 用 `pub global activate` 装）与 `plugins/tray_manager`（已被第一方 `plugins/tray` 取代）删除 |
| `arb/**`、`lib/l10n/**`、`**/generated/**` | **取上游**，合并后用 `dart run build_runner build` 重生成 |
| `README.md` / `CHANGELOG.md` / `changelog.json` | 上游整篇重写／本身就是生成物：取上游；fork 的发布说明由 `tool/changelog.dart` 在发布时重生成 |
| 测试断言 | 资源页 `FutureBuilder<FileInfo?>` 数量改成 `GeoResource.values.length`；`custom_import_test.dart` 里「app 装不下的群组」样例从 `type: smart` 换成 `type: not-a-group`（smart 现在是支持的） |

### fork 本地 CI 分歧（`build.yaml` 是上游文件，merge/rebase 后必须保留这几处）

- `upload` job 用 `if: false` 关掉。上游那个 job 会通过他们 runner 上的本地 bot API（`localhost:8081`）
  往 `@FlClash` 频道播报，然后删掉并重新发布 tag 的 release；本 fork 唯一的发布者是 `release-android-arm64.yml`。
- `Setup Android Signing` 只在 `KEYSTORE` secret 存在时才写 `keystore.jks`。上游的写法会把空字符串解码到
  文件里，缺 secret 时构建直接失败。
- **不要恢复 `ref: smart`**（2026-11-16 删除）：它会让 tag/预览分支的构建去 checkout `smart`，
  默认 ref 才是对的；上游那一步需要的是 `submodules: true`。
- `tool/check_dart.sh` 跑整个 dart job（`release-android-arm64.yml` 在用）；`tool/check_submodules.sh`
  会 fetch 每个子模块指针，避免子模块 commit 还没推就推了指针。它只格式化 `lib test tool setup.dart`，
  绝不碰 vendored 的 `plugins/`。

### 合并后（必做）

```bash
# 1. 重新生成（freezed / json / riverpod / drift / l10n）
flutter pub get
dart run build_runner build

# 2. 同步 Go 依赖（两个模块都要）
cd core/Clash.Meta && go mod tidy && cd ../..
cd core && go mod tidy && cd ..

# 3. 提交：内核指针 + tidy 结果 + 生成物
git add -A
git commit
```

## 三、验证

```bash
# Go 两个模块
cd core/Clash.Meta && go build ./... && go test ./dns/ -count=1 && cd ../..
cd core && go build ./... && cd ..

# Dart 侧
flutter analyze --no-fatal-infos
flutter test

# Smart / 适配标记
grep -c 'case "smart"' core/Clash.Meta/adapter/outboundgroup/parser.go     # 1
ls core/Clash.Meta/component/smart/*.go | wc -l                            # 7（cache/exit/response/stats/store/target/weight）
ls core/Clash.Meta/dns/patch.go core/Clash.Meta/component/resolver/patch.go # chen 的适配
grep -c 'GeoUpdateHook' core/Clash.Meta/component/updater/patch.go         # 3
grep -c 'smartTarget' core/Clash.Meta/tunnel/statistic/manager.go          # 4
grep -n 'MODEL' core/geo.go                                               # 1（MODEL 资源）
```

**`flutter analyze` 在含中文的路径下会崩**（分析服务器的 LSP 报文按字符而不是字节算长度，
`…/DSH项目/…` 直接把 `analysis server exited with code 255` 打出来）。把仓库放到纯 ASCII 路径下再跑，
或 `tar` 一份到 `/home/<你>/work/flclash` 里跑；git 操作仍留在原路径。

```bash
# Android APK（只编 arm64，需要 SDK + NDK，本机没有就跳过并说明）
export ANDROID_HOME=<你的 SDK>
flutter build apk --debug --target-platform android-arm64
```

- Flutter 用 CI 的 pin 版本（`.github/workflows/build.yaml` 的 `FLUTTER_VERSION`，当前 `3.47.6`）。
  `riverpod_generator` 需要 Dart ≥3.12，旧版 Flutter 的 `pub get` 会失败。
- NDK 用 `android/gradle/libs.versions.toml` 的 `ndkVersion`。
- Rust helper（`services/helper`）需要 `cargo`/`rustc` 在 PATH 上；hook 会忽略 `RUSTC` 环境变量。
- `File modified during build. Build must be rerun.` 是已知现象：Go core hook 在构建期间写
  `android/core/src/main/jniLibs`，Flutter 会自动重跑一次 gradle。

## 四、Smart 功能验证清单

- [ ] `assets/data/Model.bin` 存在，且与 `assets/data/Model.bin.sha256` 一致（30 特征模型：文件头 `max_feature_idx=29`）
  内核的 `IsCompatible()` 要求特征序 32 槽，30 槽的模型会被自动补齐（`30: exit_region`、`31: exit_asn_hash`），
  老模型仍可用，只是这两槽为 0
- [ ] `lib/common/constant.dart` 有 `MODEL`、`modelAssetHashFile`、`modelAssetHashKey`
- [ ] `lib/enum/enum.dart` 的 `GeoResource` 含 `MODEL`（`@JsonValue('model')`）与 `configKey`
- [ ] `lib/models/clash_config.dart` 的 `defaultGeoXUrl` 含 `GeoResource.MODEL`，`ProxyGroup` 有 5 个 smart 字段
- [ ] `lib/views/resources.dart` 的 `_geoFileName` 处理 `GeoResource.MODEL`
- [ ] `lib/common/task.dart` 写 `lgbm-url`，且 `geox-url` 里不含 `model`
- [ ] `core/geo.go` 有 `"MODEL"` 资源；`core/proxy.go` 的 `isProxyGroupType` 含 `constant.Smart`
- [ ] **已知缺口（不是回归）**：群组编辑器没有 smart 选项行——上游删掉了 fork 曾扩写的
  `lib/views/profiles/overwrite/custom/groups.dart` 整页。6 个 smart 字段只存在于 DB（`definition` JSON）
  与 `lib/models/clash_config.dart`，编辑器改自己那几行时其余字段由 `ProxyGroup` 原地 `copyWith` 保留
  （由 `test/database/proxy_group_options_test.dart` 守护）。要「有开关」得在上游新编辑器里自己加行。
- [ ] 内核有 `case "smart"`、`component/smart/`、`component/updater/patch.go` 的 `GeoUpdateHook`
- [ ] 内核有 chen 适配的 `dns/patch.go`、`component/resolver/patch.go`、`tunnel/patch.go` 的 `*Snapshot`
- [ ] `core/` 与 `core/Clash.Meta/` 两个模块 `go build ./...` 通过
- [ ] arm64 APK 构建通过，`lib/arm64-v8a/{libclash.so,libcore.so,librust_api.so}` 均为 aarch64
