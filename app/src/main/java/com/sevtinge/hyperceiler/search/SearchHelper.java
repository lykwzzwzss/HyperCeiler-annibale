package com.sevtinge.hyperceiler.search;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.content.res.XmlResourceParser;
import android.database.sqlite.SQLiteException;
import android.graphics.Color;
import android.text.TextUtils;

import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceGroup;
import androidx.preference.PreferenceScreen;

import com.sevtinge.hyperceiler.common.log.AndroidLog;
import com.sevtinge.hyperceiler.dashboard.DashboardFragment;
import com.sevtinge.hyperceiler.home.HomePageFragment;
import com.sevtinge.hyperceiler.libhook.utils.api.ThreadPoolManager;
import com.sevtinge.hyperceiler.prefs.LayoutPreference;
import com.sevtinge.hyperceiler.prefs.PreferenceHeader;
import com.sevtinge.hyperceiler.search.data.AppDatabase;
import com.sevtinge.hyperceiler.search.data.ModDao;
import com.sevtinge.hyperceiler.search.data.ModEntity;
import com.sevtinge.hyperceiler.utils.LanguageHelper;

import org.xmlpull.v1.XmlPullParser;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class SearchHelper {

    private static final String TAG = "SearchHelper";

    public static final int MARK_COLOR_VIBRANT = Color.parseColor("#277af7");
    private static final String ANDROID_NS = "http://schemas.android.com/apk/res/android";
    private static final String APP_NS = "http://schemas.android.com/apk/res-auto";

    private static final Set<Integer> REGISTERED = Collections.synchronizedSet(new HashSet<>());

    private static final Map<String, String> GROUP_PACKAGE_MAP = new LinkedHashMap<>();

    private static final Map<String, Integer> GROUP_ICON_MAP = new LinkedHashMap<>();

    public static Map<String, String> getGroupPackageMap() {
        return Collections.unmodifiableMap(GROUP_PACKAGE_MAP);
    }

    public static Map<String, Integer> getGroupIconMap() {
        return Collections.unmodifiableMap(GROUP_ICON_MAP);
    }

    public static void initIndex(Context context, boolean force) {
        AndroidLog.d(TAG, "initIndex: force = " + force);
        ThreadPoolManager.getInstance().submit(() -> {
            ModDao dao = AppDatabase.getInstance(context).modDao();
            if (force || dao.getCount() == 0) {
                rebuildIndex(context, dao);
            }
        });
    }

    private static void rebuildIndex(Context context, ModDao dao) {
        REGISTERED.clear();
        Resources res = getLocaleResources(context);
        List<ModEntity> entities = new ArrayList<>();

        int headersXmlResId = HomePageFragment.getHomeHeadersResourceId();
        if (headersXmlResId == 0) {
            AndroidLog.e(TAG, "Home headers xml not found!");
            return;
        }

        scanHeaders(res, headersXmlResId, entities);

        AppDatabase.getInstance(context).runInTransaction(() -> {
            dao.deleteAll();
            dao.insertAll(entities);
            AndroidLog.d(TAG, "rebuildIndex: inserted " + entities.size() + " entries");
        });
    }

    private static void scanHeaders(Resources res, int headersXmlResId, List<ModEntity> entities) {
        GROUP_PACKAGE_MAP.clear();
        GROUP_ICON_MAP.clear();
        try (XmlResourceParser xml = res.getXml(headersXmlResId)) {
            int eventType = xml.getEventType();
            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG && isPreferenceHeaderTag(xml.getName())) {
                    String fragment = xml.getAttributeValue(ANDROID_NS, "fragment");
                    String titleAttr = xml.getAttributeValue(ANDROID_NS, "title");
                    String summaryAttr = xml.getAttributeValue(ANDROID_NS, "summary");
                    String inflatedXmlAttr = xml.getAttributeValue(APP_NS, "inflatedXml");
                    String iconAttr = xml.getAttributeValue(ANDROID_NS, "icon");

                    String groupTitle = resolveStringRef(res, titleAttr);
                    String packageName = resolveStringRef(res, summaryAttr);
                    if (packageName == null) packageName = summaryAttr;
                    int inflatedXml = resolveResId(inflatedXmlAttr);
                    int iconResId = resolveResId(iconAttr);

                    if (groupTitle != null && packageName != null) {
                        GROUP_PACKAGE_MAP.put(groupTitle, packageName);
                    }
                    if (groupTitle != null && iconResId > 0) {
                        GROUP_ICON_MAP.put(groupTitle, iconResId);
                    }

                    if (inflatedXml > 0) {
                        String frag = fragment != null ? fragment : DashboardFragment.class.getName();
                        scanEntryXml(res, frag, inflatedXml, groupTitle, groupTitle, entities);
                    } else if (fragment != null) {
                        int xmlResId = getXmlResIdFromFragment(fragment);
                        if (xmlResId > 0) {
                            scanEntryXml(res, fragment, xmlResId, groupTitle, groupTitle, entities);
                        }
                    }
                }
                eventType = xml.next();
            }
        } catch (Throwable t) {
            AndroidLog.e(TAG, "Failed to scan home headers", t);
        }
    }

    private static void scanEntryXml(Resources res, String fragment, int xmlResId,
                                     String topLevelGroup, String parentBreadcrumb,
                                     List<ModEntity> entities) {
        if (!REGISTERED.add(xmlResId)) return;

        try (XmlResourceParser xml = res.getXml(xmlResId)) {
            int eventType = xml.getEventType();
            int order = 0;
            String location = null;
            int locationId = 0;
            List<ModEntity> batch = new ArrayList<>();
            DashboardFragment availability = getAvailabilityFragment(fragment);
            int hiddenDepth = -1;

            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.END_TAG && xml.getDepth() == hiddenDepth) {
                    hiddenDepth = -1;
                }
                if (eventType == XmlPullParser.START_TAG) {
                    if (hiddenDepth >= 0) {
                        eventType = xml.next();
                        continue;
                    }
                    String key = xml.getAttributeValue(ANDROID_NS, "key");
                    boolean hidden = "false".equals(xml.getAttributeValue(APP_NS, "isPreferenceVisible"));
                    boolean disabled = "false".equals(xml.getAttributeValue(ANDROID_NS, "enabled"));
                    if (hidden || disabled || (key != null && availability != null
                        && !availability.isPreferenceAvailableForSearch(key))) {
                        hiddenDepth = xml.getDepth();
                        eventType = xml.next();
                        continue;
                    }
                    String tag = xml.getName();

                    if (isPreferenceScreenTag(tag)) {
                        String myLoc = xml.getAttributeValue(APP_NS, "myLocation");
                        if (myLoc != null && location == null) {
                            location = resolveStringRef(res, myLoc);
                            locationId = resolveResId(myLoc);
                        }
                    } else if (!shouldSkipTag(tag)) {
                        String titleAttr = xml.getAttributeValue(ANDROID_NS, "title");
                        String keyAttr = xml.getAttributeValue(ANDROID_NS, "key");
                        String isVisibleAttr = xml.getAttributeValue(APP_NS, "isPreferenceVisible");
                        String childFragment = xml.getAttributeValue(ANDROID_NS, "fragment");
                        String persistentAttr = xml.getAttributeValue(ANDROID_NS, "persistent");

                        String modTitle = resolveStringRef(res, titleAttr);
                        boolean isHidden = "false".equals(isVisibleAttr);
                        // persistent=false 的动作项（如编辑入口、预览按钮）不是可搜索的设置项
                        boolean isActionOnly = "false".equals(persistentAttr);

                        if (childFragment != null) {
                            int childXml = getXmlResIdFromFragment(childFragment);
                            if (childXml > 0) {
                                String breadcrumb = buildBreadcrumb(parentBreadcrumb, location);
                                scanEntryXml(res, childFragment, childXml, topLevelGroup, breadcrumb, entities);
                            }
                        }

                        if (!TextUtils.isEmpty(modTitle) && !TextUtils.isEmpty(keyAttr) && !isHidden
                            && !isActionOnly) {
                            ModEntity mod = new ModEntity();
                            mod.title = modTitle;
                            mod.key = keyAttr;
                            mod.xmlResId = xmlResId;
                            mod.order = order;
                            mod.fragment = fragment;
                            mod.breadcrumbs = buildBreadcrumb(parentBreadcrumb, location);
                            mod.catTitleResId = locationId;
                            mod.groupName = topLevelGroup != null ? topLevelGroup : "";
                            batch.add(mod);
                        }
                        order++;
                    }
                }
                eventType = xml.next();
            }

            if (!batch.isEmpty()) entities.addAll(batch);
        } catch (Throwable t) {
            AndroidLog.e(TAG, "Failed to parse XML: " + xmlResId, t);
        }
    }

    public static List<ModEntity> search(Context context, String keyword) {
        List<ModEntity> empty = new ArrayList<>();
        if (TextUtils.isEmpty(keyword)) return empty;
        String trimmed = keyword.trim();
        if (trimmed.isEmpty()) return empty;

        try {
            ModDao dao = AppDatabase.getInstance(context).modDao();
            // 中文（简体/繁体）按字模糊匹配，其它语言按词汇匹配
            if (containsChinese(trimmed)) {
                return dao.testSearch(trimmed);
            }
            String query = buildFtsQuery(trimmed);
            if (query == null) return empty;
            return dao.search(query);
        } catch (SQLiteException e) {
            // FTS 语法错误/索引损坏时不要向上抛，否则调用方无法关闭 loading 状态
            AndroidLog.e(TAG, "search failed, keyword=" + keyword, e);
            return empty;
        } catch (RuntimeException e) {
            AndroidLog.e(TAG, "search failed, keyword=" + keyword, e);
            return empty;
        }
    }

    /**
     * 构造 FTS4 MATCH 表达式：过滤 FTS 运算符，双写转义双引号，再整体包成短语前缀查询。
     */
    private static String buildFtsQuery(String input) {
        if (input == null) return null;
        String trimmed = input.trim();
        if (trimmed.isEmpty()) return null;

        StringBuilder sanitized = new StringBuilder(trimmed.length());
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (isFtsOperator(c)) {
                sanitized.append(' ');
            } else if (c == '"') {
                sanitized.append("\"\"");
            } else {
                sanitized.append(c);
            }
        }

        String cleaned = sanitized.toString().trim();
        if (cleaned.isEmpty() || isOnlyQuotes(cleaned)) return null;
        return "\"" + cleaned + "\"*";
    }

    private static boolean isFtsOperator(char c) {
        switch (c) {
            case '*':
            case '-':
            case '^':
            case ':':
            case '(':
            case ')':
            case '+':
            case '~':
            case '{':
            case '}':
            case '[':
            case ']':
            case '/':
            case '\\':
            case ';':
            case '\'':
            case '<':
            case '>':
                return true;
            default:
                return false;
        }
    }

    private static boolean isOnlyQuotes(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) != '"') return false;
        }
        return true;
    }

    private static boolean containsChinese(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 0x4E00 && c <= 0x9FFF  // CJK Unified Ideographs
                || c >= 0x3400 && c <= 0x4DBF  // CJK Extension A
                || c >= 0xF900 && c <= 0xFAFF) { // CJK Compatibility Ideographs
                return true;
            }
        }
        return false;
    }

    public static void clearIndex() {
        REGISTERED.clear();
        GROUP_PACKAGE_MAP.clear();
        GROUP_ICON_MAP.clear();
    }

    private static Resources getLocaleResources(Context context) {
        Configuration config = new Configuration(context.getResources().getConfiguration());
        config.setLocale(LanguageHelper.getCurrentLocale(context));
        return context.createConfigurationContext(config).getResources();
    }

    /**
     * 只有本模块自己的设置页类才允许被反射加载/实例化；名字来自偏好 XML，必须先约束。
     */
    private static boolean isInternalFragmentClass(String name) {
        return name != null && name.startsWith("com.sevtinge.hyperceiler.");
    }

    private static DashboardFragment getAvailabilityFragment(String fragmentName) {
        if (!isInternalFragmentClass(fragmentName)) {
            return null;
        }
        try {
            Class<?> clazz = Class.forName(fragmentName);
            if (!DashboardFragment.class.isAssignableFrom(clazz)) {
                return null;
            }
            return (DashboardFragment) clazz.getDeclaredConstructor().newInstance();
        } catch (Throwable t) {
            AndroidLog.e(TAG, "Cannot read search availability: " + fragmentName, t);
            return null;
        }
    }

    private static int getXmlResIdFromFragment(String fragmentName) {
        if (!isInternalFragmentClass(fragmentName)) {
            return 0;
        }
        try {
            Class<?> clazz = Class.forName(fragmentName);
            Object instance = clazz.getDeclaredConstructor().newInstance();
            Method method = findMethod(clazz, "getPreferenceScreenResId");
            if (method != null) {
                method.setAccessible(true);
                Object result = method.invoke(instance);
                if (result instanceof Integer) return (int) result;
            }
        } catch (Throwable ignored) {}
        return 0;
    }

    private static boolean shouldSkipTag(String tag) {
        return isPreferenceCategoryTag(tag)
            || isLayoutPreferenceTag(tag)
            || isContainerTag(tag);
    }

    private static boolean isPreferenceHeaderTag(String tag) {
        return "header".equals(tag) || isTagOrSubclass(tag, PreferenceHeader.class);
    }

    private static boolean isPreferenceScreenTag(String tag) {
        return isTagOrSubclass(tag, PreferenceScreen.class);
    }

    private static boolean isPreferenceCategoryTag(String tag) {
        return isTagOrSubclass(tag, PreferenceCategory.class);
    }

    private static boolean isLayoutPreferenceTag(String tag) {
        return isTagOrSubclass(tag, LayoutPreference.class);
    }

    private static boolean isContainerTag(String tag) {
        Class<?> tagClass = loadTagClass(tag);
        return tagClass != null && PreferenceGroup.class.isAssignableFrom(tagClass);
    }

    private static boolean isTagOrSubclass(String tag, Class<?> targetClass) {
        if (TextUtils.isEmpty(tag)) {
            return false;
        }
        if (targetClass.getSimpleName().equals(tag) || targetClass.getName().equals(tag)) {
            return true;
        }
        Class<?> tagClass = loadTagClass(tag);
        return tagClass != null && targetClass.isAssignableFrom(tagClass);
    }

    private static Class<?> loadTagClass(String tag) {
        if (TextUtils.isEmpty(tag) || !tag.contains(".")) {
            return null;
        }
        try {
            return Class.forName(tag);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Method findMethod(Class<?> clazz, String name) {
        while (clazz != null) {
            try { return clazz.getDeclaredMethod(name); }
            catch (NoSuchMethodException e) { clazz = clazz.getSuperclass(); }
        }
        return null;
    }

    private static String buildBreadcrumb(String parent, String location) {
        if (parent == null && location == null) return "";
        if (parent == null) return location;
        if (location == null) return parent;
        if (parent.equals(location)) return parent;
        return parent + "/" + location;
    }

    private static String resolveStringRef(Resources res, String attr) {
        if (attr == null) return null;
        if (attr.startsWith("@")) {
            try {
                int id = Integer.parseInt(attr.substring(1));
                if (id > 0) return res.getString(id);
            } catch (Exception ignored) {}
        }
        return null;
    }

    private static int resolveResId(String attr) {
        if (attr == null || !attr.startsWith("@")) return 0;
        try {
            int id = Integer.parseInt(attr.substring(1));
            return Math.max(id, 0);
        } catch (NumberFormatException e) { return 0; }
    }
}
