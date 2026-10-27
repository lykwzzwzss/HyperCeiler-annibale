# 通过 ADB 读写 HyperCeiler 配置（仅 Debug 版）

ADB 接口仅编入 debug 构建；release、beta、canary 均不包含接口实现和 Provider 入口。下文 4710 是早期验证版本编号，后续必须安装 Debug 版。现有 4710/4711 APK 不会随源码修改而改变，需要重新构建并安装才能移除接口。

4710 新增接口：`content://com.sevtinge.hyperceiler.adb`。支持正常 ADB shell 和 root；普通应用即使获得 DUMP 权限也不能调用。无需把 ADB 提升为 root，不需要额外界面开关。

## 安装与确认

覆盖安装当前 Debug 版，打开一次 HyperCeiler，保留模块原有作用域。随后在电脑执行：

```powershell
.\tools\hyperceiler-adb.ps1 status
```

`version_code` 应与安装的 Debug 版一致，`remote_bound` 和 `remote_synced` 都为 `true`。如果还没有连接框架，返回值会注明只保存到本地、等待同步；打开模块后再查 `status`。同步失败时会返回失败状态，不会将其报告为已在 Hook 中生效。

## 常用操作

在仓库目录运行以下命令。支持 `-Serial YOUR_DEVICE_SERIAL` 指定手机。

```powershell
# 查询一项；可以省略 prefs_key_ 前缀
.\tools\hyperceiler-adb.ps1 get system_framework_volume_media_steps_enable

# 查看某一组配置及每项存储类型
.\tools\hyperceiler-adb.ps1 list system_framework_

# 打开/关闭功能
.\tools\hyperceiler-adb.ps1 set system_framework_freeform_sticky true -Type boolean
.\tools\hyperceiler-adb.ps1 set system_framework_freeform_sticky false -Type boolean

# 修改整数；下拉选项通常存储为 string，不能误写成 int
.\tools\hyperceiler-adb.ps1 set system_framework_volume_media_steps 25 -Type int
.\tools\hyperceiler-adb.ps1 set log_level_v2 2 -Type string

# 导出配置；或应用一份 JSON 补丁/模块备份
.\tools\hyperceiler-adb.ps1 export -File before-test.json
.\tools\hyperceiler-adb.ps1 apply -File changes.json

# 删除测试中新增的键，使其重新使用程序默认值
.\tools\hyperceiler-adb.ps1 remove system_framework_freeform_sticky
```

接口只保存配置。大部分 Hook 在目标进程启动时决定是否安装，并缓存参数。**系统框架项目改后重启手机；其他应用改后重开其进程，无法确认时重启手机。** 已打开的模块设置页可能要退出重开才刷新。接口不会自动重启手机或强停目标应用。

## 批量文件格式

```json
{
  "prefs_key_log_level_v2": "2",
  "prefs_key_system_framework_freeform_sticky": true,
  "prefs_key_system_framework_volume_media_steps": 25,
  "prefs_key_new_test_key": null
}
```

JSON 对象中出现的键会被修改，其他键保留；`null` 删除该键。整个批次先校验，再以一次本地提交写入并同步到 LSPosed。两个存储之间没有分布式事务：远端失败时本地可能已保存，返回信息会明确区分。批次最多 512 个键、65536 个字符，Base64 传输长度还需不超过 90000 个字符。

已有键必须保持原类型；新键的布尔、字符串、整数和字符串数组会按 JSON 类型建立。新建 `long` 或 `float` 时用 `set -Type long/float`。数组只能包含字符串，对应 StringSet。框架激活状态与版本检测状态只允许读取，不能伪造。

接口面向功能偏好读写，不会自动执行设置页的额外操作，例如申请作用域、变更桌面入口组件或修改 Android 系统设置。此类动作仍使用对应页面。

**恢复注意：**应用原备份会恢复备份里列出的键，但不会自动删除备份之外的新键。测试前保存新增键名单，恢复时对这些键用 `null` 或 `remove`。导出文件跳过只读框架状态，便于重新应用。

## 不使用脚本的原始 ADB 命令

```text
adb shell content call --uri content://com.sevtinge.hyperceiler.adb --method status
adb shell content call --uri content://com.sevtinge.hyperceiler.adb --method get --arg system_framework_freeform_sticky
adb shell content call --uri content://com.sevtinge.hyperceiler.adb --method set --arg system_framework_freeform_sticky --extra type:s:boolean --extra value:s:true
adb shell content call --uri content://com.sevtinge.hyperceiler.adb --method remove --arg system_framework_freeform_sticky
```

`status/get/list/set/remove/apply` 返回 Bundle：`ok` 表示操作状态，`data` 是 UTF-8 JSON 的 Base64。使用配套 PowerShell 脚本会自动解码。`apply` 通过 `--extra payload:s:BASE64` 传入 JSON；含引号、换行或中文的单项值可通过 `--extra value64:s:BASE64` 传入。

## 本轮日志验证

1. 安装 4710，打开模块确认同步成功。先保留原备份。
2. 用测试配置批量开启本轮待核对功能，并将 `log_level_v2` 设为字符串 `"2"`（完整日志），然后重启手机。
3. 解锁后触发对应功能：播放带封面的媒体并展开控制中心/灵动岛，打开小窗与分享选择页，打开系统休眠和 USB 设置、安全中心、应用商店与息屏设置；手电筒亮起后拖亮度条，再关闭。
4. 完成后先告诉我“开启测试完成”，保持 ADB 连接，等我采集本次启动日志。不要提前重启或清日志。
5. 我采集后，再批量关闭测试功能，重启并重复相同操作；告诉我“关闭测试完成”。最后恢复原配置和原日志等级。

完整日志可证明部分 Hook 的初始化结果；“Hook Success”不等于每个条件分支都已触发。蓝牙音量、息屏持续时间、温控亮度、超过数量上限的通知等，需要相应场景才能确认实际效果；条件不具备时标记“未触发”。不需要为测试主动加热手机。
