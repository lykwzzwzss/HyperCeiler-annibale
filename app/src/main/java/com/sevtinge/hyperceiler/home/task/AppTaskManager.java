package com.sevtinge.hyperceiler.home.task;

import static com.sevtinge.hyperceiler.utils.NoticeProcessor.isNeedShowTosDialog;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.sevtinge.hyperceiler.common.utils.PrefsBridge;
import com.sevtinge.hyperceiler.common.utils.shell.ShellInit;
import com.sevtinge.hyperceiler.home.data.AppInfoCache;
import com.sevtinge.hyperceiler.home.manager.PageDecorator;
import com.sevtinge.hyperceiler.libhook.utils.pkg.CheckModifyUtils;
import com.sevtinge.hyperceiler.search.SearchHelper;
import com.sevtinge.hyperceiler.ui.HomePageActivity;
import com.sevtinge.hyperceiler.utils.LSPosedScopeHelper;
import com.sevtinge.hyperceiler.utils.LanguageHelper;
import com.sevtinge.hyperceiler.utils.LogServiceUtils;
import com.sevtinge.hyperceiler.utils.NoticeProcessor;

import java.util.List;

import io.github.libxposed.service.XposedServiceHelper;

/**
 * 业务管理类：定义任务图及原本属于 Activity 的计算逻辑
 */
public class AppTaskManager {

    private static final List<String> CHECK_LIST = List.of(
        "com.miui.securitycenter",
        "com.android.camera",
        "com.miui.home"
    );

    private static final String ACTIVITY_TASK_SEPARATOR = "#";

    public static void attach(Context context) {
        TaskRunner runner = TaskRunner.getInstance();

        runner.addTask(new Task("attachBaseContext", false) { // 主线程同步执行
            @Override
            public void execute() {
                if (context instanceof XposedServiceHelper.OnServiceListener listener) {
                    XposedServiceHelper.registerListener(listener);
                }
                PrefsBridge.initForApp(context);
            }
        });
    }

    /**
     * 第一阶段任务定义：Application 级别
     */
    public static void setupAppTasks(Context context) {
        TaskRunner runner = TaskRunner.getInstance();

        runner.addTask(new Task("LanguageInit", false) { // 主线程同步执行
            @Override
            public void execute() {
                // 提前初始化语言，保证首屏渲染就是正确的语言
                LanguageHelper.init(context);
            }
        });

        runner.addTask(new Task("BaseEnv", false) {
            @Override
            public void execute() {
                LSPosedScopeHelper.init();
                AppInfoCache.getInstance(context).init();
            }
        });
    }

    /**
     * 第二阶段任务定义：Activity 级别
     */
    public static void setupActivityTasks(HomePageActivity activity, Context context) {
        TaskRunner runner = TaskRunner.getInstance();

        // UI 装饰任务：必须在主线程执行 (isAsync = false)
        runner.addTask(new Task(activityTaskId(activity, "UI_Theme"), false) {
            @Override
            public void execute() {
                // 调用装饰器：处理滤镜和节日特效
                PageDecorator.decorate(activity);
                LogServiceUtils.init(activity);

                new Thread(() -> {
                    NoticeProcessor.NoticeResult result = NoticeProcessor.process(context);
                    if (result != null) {
                        if (isNeedShowTosDialog(result)) {
                            new Handler(Looper.getMainLooper()).post(() -> {
                                NoticeProcessor.showTosDialog(context, result);
                            });
                        }
                        if (result.id() != -100) {
                            new Handler(Looper.getMainLooper()).post(() -> {
                                NoticeProcessor.showNoticeDialog(context, result);
                            });
                        }
                    }
                }).start();
            }
        });

        // 异步业务逻辑：初始化 Shell、执行签名校验
        runner.addTask(new Task(activityTaskId(activity, "BusinessLogic"), true, "BaseEnv", "attachBaseContext") {
            @Override
            public void execute() {
                ShellInit.init(activity);
                // 原 Activity 的 checkAppMod 逻辑迁移到此
                CHECK_LIST.parallelStream().forEach(pkg -> {
                    checkAppMod(activity, pkg);
                });
            }
        });

        runner.addTask(new Task(activityTaskId(activity, "UI_Effect"), false) {
            @Override
            public void execute() {
                SearchHelper.initIndex(activity, true);
            }
        });
    }

    private static void checkAppMod(Context context, String pkg) {
        boolean check = CheckModifyUtils.INSTANCE.isApkModified(context, pkg, CheckModifyUtils.XIAOMI_SIGNATURE);
        CheckModifyUtils.INSTANCE.setCheckResult(pkg, check);
    }

    /**
     * 生成 Activity 级任务 id：activity hash + task id，页面重建后不会命中旧实例的去重 key
     */
    private static String activityTaskId(HomePageActivity activity, String taskId) {
        return activityTaskPrefix(activity) + taskId;
    }

    private static String activityTaskPrefix(HomePageActivity activity) {
        return System.identityHashCode(activity) + ACTIVITY_TASK_SEPARATOR;
    }

    /**
     * Activity 销毁时清理其任务，避免旧 Activity 被任务闭包长期持有
     */
    public static void releaseActivityTasks(HomePageActivity activity) {
        TaskRunner.getInstance().removeTasksWithPrefix(activityTaskPrefix(activity));
    }
}
