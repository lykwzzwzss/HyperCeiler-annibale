# REDMI K90 适配

基于 wjy1065676928/HyperCeiler-myron 的 7ea5aba57，目标系统为 REDMI K90 / annibale，HyperOS 4.0.0.32 / Android 17。

- Root 检查：兼容当前 guardprovider 的 boolean 返回值及旧版 String 返回值。
- 手电筒调光：使用后置相机提供的 100 级接口，接管控制中心滑块，避免修改屏幕亮度；保留旧设备节点回退。
- 桌面抽屉：撤回作者针对桌面 6.9/7.0 的专项修复，恢复上游实现。
- 亮度上限：已核对 BrightnessRangeController 接口，保留原功能。
- 新增「禁止高温降低亮度上限」：系统框架 → 其他 → 显示与通知。默认关闭，修改后重启。仅解除已核对的软件显示温控限制，可能增加发热与耗电。

修改的手电筒、Root 返回值辅助类和温控亮度 Hook 已通过针对 Android 37、EzHookTool 1.3 / libxposed 102 的局部编译。相机选择、亮度边界、Root 返回值及显示温控 Hook 的独立回归检查通过；不代表安装后的功能实测。

完整 Canary APK 已构建成功：[构建任务 37880619735](https://github.com/lykwzzwzss/HyperCeiler-myron/actions/runs/37880619735)。对应源码提交 4832c3297111be18cc34f40420f68c965f7e9d51，版本代码 4693，版本名称 2.10.166-4832c3297-r4693。构建使用 GitHub Actions 临时只读令牌，未使用本机长期登录令牌。

APK SHA-256：`e156b5ef84798877190320dcdc9d964750516f1b4de28b10df6437a997ca66d1`。

Android 17 / ARM64 包信息及 APK v2 签名校验通过。当前使用测试签名，不能保证覆盖安装其他签名的版本；尚未安装到手机实测。

后续可在 Actions 中手动运行 **K90 APK Build**。

## 媒体音量与启动流程更新

- 媒体音量选项改为「媒体音量按键档位」，范围 15–45，15 保留系统默认行为。
- 不再修改 ro.config.media_vol_steps，不在 SystemUI 进程安装音量属性 Hook。K90 原生 150 级滑块与蓝牙音量最大值保持一致。
- 只处理普通媒体加减请求的步长。蓝牙绝对音量回传、指定设备请求、通话/铃声及静音请求不进入自定义档位逻辑。系统原有安全音量与设备策略继续执行。
- 按原生可见音量索引划分等距档位，24 档使用相邻 6/7 级的间隔，30 档每次 5 级。拖动后的值先按最近档位继续加减，避免极小的第一次调整。
- 测试包含 16–45 档往返计数、蓝牙 AVRCP 0–127 往返取整、任意滑块起点及边界。真实 Hook API 的调用链检查另验证嵌套/线程隔离、异常回退与原生范围保留。
- 移除首次协议确认时的验证码输入及验证码展示；保留勾选同意流程。
- 移除主页因自建签名而显示的「签名校验失败」横幅。
- 后续 Actions 构建使用固定的 K90 签名密钥。旧版 4693 的临时调试密钥未保留，本次更新可能无法直接覆盖；后续使用固定密钥的版本可保持签名一致。

安装本轮更新后需重启手机使系统框架音量 Hook 生效。上述音量测试是算法与调用链检查，不等于手机安装后的功能实测。

### 新版构建验证（2026-10-09）

- APK：`2.10.166-97048f983-r4696`，版本号 `4696`，源提交 `97048f983a469c4e38e91ad5ae49a0eec298f2cc`。
- [构建记录](https://github.com/lykwzzwzss/HyperCeiler-myron/actions/runs/37909288449)：成功；5 项音量单元测试通过，失败/错误/跳过均为 0。
- 文件 SHA-256：`ff0ee7ebd3c5151d51f12170b657bb17d5db43b8ead22ee01f4a05db3b09c89e`。
- APK v3 签名有效，固定证书 SHA-256：`da13c8e9e62dc613cb48e2023589aece6d964537dc35f3ead72f2441148fd3a9`。
- 最低 Android API 35，目标 API 37，`arm64-v8a`。
- 尚未安装到 K90 验证实际按键及蓝牙同步。签名与旧版 4693 不同，覆盖安装失败时应先备份配置再重装；安装后重启，以重新加载系统框架音量钩子。

### 首页提示调整（2026-10-09）

按使用者要求，移除首页“当前设备运行的系统版本未（完全）适配”的通用红字。保留 `DeviceHelper.System` 中原有的系统支持状态、支持版本页面及具体功能兼容性判断。


## 锁屏人脸解锁岛

- 功能开关：系统界面 → 锁屏 → 其他 → 锁屏人脸解锁岛。默认关闭，开启后重启系统界面。
- 两段补丁 dex 以 `app/src/main/assets/faceisland/*.dex` 和 `library/libhook/src/main/resources/faceisland/*.dex` 随模块分发，在 `miui.systemui.plugin` 的 ClassLoader 下通过 `InMemoryDexClassLoader` 加载。
- SystemUI 侧按补丁注入点改写为 Xposed Hook，覆盖人脸认证、指纹、凭据、休眠/唤醒、动态岛发布与原生人脸图标隐藏；插件侧接管模板工厂的创建、显隐、宽度和移除。
- 仅对 `annibale` 生效，依赖 HyperOS 4 的动态岛插件组件 `miui.systemui.notification.NotificationDynamicIslandPluginImpl`。
- 已在 K90（annibale，OS4.0.5.0.XPKCNXM / Android 17）实测纯人脸解锁，解锁动画与灵动岛显示正常；未验证指纹/凭据动画及息屏退场动画。
