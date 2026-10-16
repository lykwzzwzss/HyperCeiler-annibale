# K90 正式版 4 项错误修复

适用记录：REDMI K90 annibale，OS4.0.2.0.XPKCNXM / Android 17 API37，HyperCeiler 4701 的启动与应用日志。目标安全中心 13.6.5-261003.0.1，系统安装器 5.5.5.0.0-20260821。

## 修改

1. 安全中心锁定 100 分：原风险项添加方法仅按 TextUtils.isEmpty / 单参数 void 匹配，误把清理失效风险项的 V(Context) 也选中，导致唯一匹配失败。现在同时要求 Map.put 调用；无须固定混淆类型名。布尔评分判断显式校验无参 boolean，缓存键升版。
2. 安装器安全模式提示屏蔽：个性化广告查询按两个字符串、无参 boolean 精确筛选；提示行隐藏改为只覆盖 SafeModeTipViewObject 实例的可见性 getter，不猜测基类多个布尔字段，也不修改其他安装器行。
3. 安装器应用信息上传屏蔽：layout 入口按实际 NewAdDataHelper 类与签名匹配，缓存解析前排除构造器/类初始化器，后续只处理 void 或 nullable Object 方法。三个上传路径独立查找，一组失效时其他组仍可加载；新缓存键避免旧错误结果复用。修复日志中的类名输出。
4. Google Quick Search Box 机型伪装：原反射 helper 不能写入 final Build 字段。保留原路径，增加与当前 Android 运行库对应的字段写入后备路径；限 Google 应用进程，写前核对字段类型、地址和原值，写后读回，失败尝试恢复，不修改系统属性或其他应用身份。具体运行验证记录保存在本地分析目录。

## 手动验收

覆盖安装候选包后，强行停止并重新打开安全中心、系统安装器和 Google 应用，使旧进程加载新版。单独开启/关闭相应功能作对比；无需为这四项重启系统框架。

- 安全中心：扫描结果按设置显示 100 分；反复进入和返回扫描页正常。日志不再有 LockOneHundredPoints 的 NonUniqueResultException。
- 安装器提示：打开一个本地可信 APK 的安装确认页即可，不必真的安装。开启后安全模式提示行隐藏，其余应用信息、安装/取消按钮可用；关闭后恢复。不要以关闭系统安全扫描或改变安全模式开关作为预期。
- 信息上传屏蔽：在同一安装确认流程中检查没有构造器解析错误、页面卡死或崩溃。本修复只覆盖列出的三个既有路径，不等于整个安装器无网络通信；是否实际阻断对应请求需要匹配请求级证据，不能只看广告消失或错误日志消失。
- Google：重新打开 Google 应用，查看启动日志不再因 Build.MANUFACTURER final 写入失败；Pixel 机型相关行为需按原实际使用场景确认。字段读回成功也不等于所有已内联/缓存的应用读取都变化。

已有音量、截图、小窗、亮度及首页修改保留。此次不改手机配置，不自动重启或覆盖安装。代码和构建通过不等于以上四项已全部实机验收。

## 静态证据

手机拉取 APK SHA256：
- securitycenter: f3d338484cc5fd3f3fec9e8d9d0b4c6370923fba0f5e0d81975a45e914cbc776
- packageinstaller: 9a50f0c8c7263a4469bef466489f0dcb89c0ed1bc56b1ecd3c19204a19ef3787

REA 保留记录：安装器身份 ev_c65539cb09d5b701251b3eddc5f4014786ea4a3e500ba668b308a992bb21363c；提示父类可见性 getter ev_20db2243b5700abaa77239d2f4446ed729f03ba62e9e45267dba347c9d054fed；广告查询 ev_3a8acf2721f279fa2a8f8ff46491b616e0f0352e87b4cf6ba6873f3b056d0036；layout helper 签名 ev_8aba785bb3c496db5cfb6908e2d7d465b3a617f7e06ed2f86b1daabd949753ac。安全中心方法语义与调用点另有本地 LockOneHundredPoints-analysis.md。

REA 对 APK 的声明/代码检查属于静态证据，未执行 APK，也未验证该 APK 的签名。core-oj.jar 不在该 REA Android provider 的 APK 输入范围内，改用已安装 JADX 对指定 Unsafe 类作有限反编译，不据此宣称检查了整个运行库。


## Google 字段写入验证边界

手机运行库实际提供的是 `jdk.internal.misc.Unsafe.staticFieldBase/staticFieldOffset` 和 `getReferenceVolatile/putReferenceVolatile`。当前 `sun.misc.Unsafe` 不提供静态字段接口，不能套用旧 Java 示例。

独立 dalvikvm 探针对 static final Object 完成写入、反射与 Unsafe 双路读回、恢复，输出 `fixture=PASS`。Build 段在类初始化时因纯 dalvikvm 宿主未注册 SystemProperties.native_get 而停止，尚未修改 Build 字段；因此不能据此宣称 Google 应用内写入成功。早先 app_process 探针被环境终止，也没有作为成功或 API 不可用的证据。临时设备探针文件已移除。
