# 自启动自动回收兼容回归

验证生产 `AutoStart.java`，通过真实 EzHook 1.3.0 / API 102 回调链执行。目标方法与 Android 类型是宿主夹具，不代表手机上的真实服务已执行。

覆盖旧接口、新版定时回收、OnePush 注册回收、注销恢复、手动关闭、其他权限、嵌套/异常后的调用范围清理、线程隔离、权限编号和接口校验、注册失败回滚。

额外验证挂接时不会初始化权限编号所属类：Android 17 的权限编号格式依赖宿主 Application 上下文，必须在系统实际处理 OnePush 时才读取字段值。提前读取会固定旧格式，导致 ECM 初始化构造超大 BigInteger，触发 Provider 发布超时。

按 `tests/PORTABILITY.md` 设置工具路径后执行：

```text
python tests/lbe-autostart/run.py --output <新的结果目录>
```

本轮手机基线：K90 / OS4.0.5.0.XPKCNXM / AuthManager 4.0.0（versionCode 400）。APK 反编译确认定时路径 `OnePushJobService$GetCloudDataTask.revokeAutoStart(Context, PackageInfo, List)`；注册路径 `PermissionManagerProvider.dealOnePush(Bundle)` 通过 `PackagePermissionRepository.isUserChangePerm(String,int,long)` 排除用户管理的自启动权限。适配只在该调用范围内使用此排除分支，不写入用户修改标记，也不拦截全局权限设置方法。
