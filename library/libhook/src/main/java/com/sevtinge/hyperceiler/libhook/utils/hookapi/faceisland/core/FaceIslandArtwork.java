package com.sevtinge.hyperceiler.libhook.utils.hookapi.faceisland.core;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 超级岛参考轮廓与成功态素材。
 *
 * <p>原补丁把两张 PNG 和 10 条路径以 Base64/字符串常量内嵌在 dex 里；移植后放到
 * {@code assets/faceisland/}，首次使用时从模块 APK 读取，绘制逻辑保持不变。</p>
 */
public final class FaceIslandArtwork {
    public static final float SOURCE_CENTER = 93.0f;
    public static final float SOURCE_RADIUS = 80.0f;
    private static final int SOURCE_WIDTH = 186;
    private static final int[] REFERENCE_COLORS = {65280, 65280, 4587322, 8585082, 10878876, 11403176, 11009957, 10616734, 10747809, 10485660};
    private static final float[] REFERENCE_OPACITIES = {0.0784f, 0.0766f, 0.1014f, 0.1436f, 0.1916f, 0.237f, 0.3107f, 0.4225f, 0.5366f, 0.7105f};
    private static volatile FaceIslandArtwork INSTANCE;

    private final Bitmap successFeatures;
    private final Bitmap successOuterRing;
    private final Path[] referencePaths;
    private final Paint paint = new Paint(3);
    private final Paint checkPaint = new Paint(3);
    private final Path successCheckPath = buildSuccessCheckPath();

    private FaceIslandArtwork(Context context) {
        this.successOuterRing = decodePng(context, "faceisland/success_outer.png");
        this.successFeatures = decodePng(context, "faceisland/success_features.png");
        this.referencePaths = buildReferencePaths(context);
        this.paint.setStyle(Paint.Style.FILL);
    }

    public static FaceIslandArtwork getInstance(Context context) {
        FaceIslandArtwork artwork = INSTANCE;
        if (artwork == null) {
            synchronized (FaceIslandArtwork.class) {
                artwork = INSTANCE;
                if (artwork == null) {
                    artwork = new FaceIslandArtwork(context);
                    INSTANCE = artwork;
                }
            }
        }
        return artwork;
    }

    /**
     * 热重载清理：断开静态缓存，让下一 generation 重新解码素材。
     * 旧视图可能仍在绘制，这里不 recycle，Bitmap 交给 GC 回收。
     */
    public static void release() {
        synchronized (FaceIslandArtwork.class) {
            INSTANCE = null;
        }
    }

    public void drawReference(Canvas canvas, float alpha) {
        if (alpha <= 0.001f) {
            return;
        }
        for (int index = 0; index < this.referencePaths.length; index++) {
            this.paint.setColor(REFERENCE_COLORS[index] | (-16777216));
            this.paint.setAlpha(Math.round(REFERENCE_OPACITIES[index] * 255.0f * clamp(alpha)));
            canvas.drawPath(this.referencePaths[index], this.paint);
        }
    }

    public void drawSuccessOuterRing(Canvas canvas, float alpha) {
        drawBitmap(canvas, this.successOuterRing, alpha);
    }

    public void drawSuccessFeatures(Canvas canvas, float alpha) {
        drawBitmap(canvas, this.successFeatures, alpha);
    }

    public void drawSuccessFeatures(Canvas canvas, float alpha, boolean checkMode) {
        if (checkMode) {
            drawSuccessCheck(canvas, alpha);
        } else {
            drawBitmap(canvas, this.successFeatures, alpha);
        }
    }

    private void drawSuccessCheck(Canvas canvas, float alpha) {
        if (alpha <= 0.001f) {
            return;
        }
        int baseAlpha = Math.round(clamp(alpha) * 255.0f);
        this.checkPaint.setStyle(Paint.Style.STROKE);
        this.checkPaint.setStrokeCap(Paint.Cap.ROUND);
        this.checkPaint.setStrokeJoin(Paint.Join.ROUND);
        this.checkPaint.setColor(-10226790);
        this.checkPaint.setStrokeWidth(15.0f);
        this.checkPaint.setAlpha(Math.round(baseAlpha * 0.22f));
        canvas.drawPath(this.successCheckPath, this.checkPaint);
        this.checkPaint.setColor(-7602255);
        this.checkPaint.setStrokeWidth(7.75f);
        this.checkPaint.setAlpha(baseAlpha);
        canvas.drawPath(this.successCheckPath, this.checkPaint);
    }

    private void drawBitmap(Canvas canvas, Bitmap bitmap, float alpha) {
        if (alpha <= 0.001f || bitmap == null) {
            return;
        }
        this.paint.setAlpha(Math.round(clamp(alpha) * 255.0f));
        canvas.drawBitmap(bitmap, 0.0f, 0.0f, this.paint);
    }

    private static Path[] buildReferencePaths(Context context) {
        try (InputStream in = FaceIslandAssets.open(context, "faceisland/reference_paths.txt");
             BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            List<String> lines = new ArrayList<>();
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isEmpty()) {
                    lines.add(line);
                }
            }
            Path[] paths = new Path[lines.size()];
            for (int index = 0; index < paths.length; index++) {
                paths[index] = parsePolygon(lines.get(index));
            }
            if (paths.length == 0) {
                throw new IllegalStateException("参考轮廓素材为空");
            }
            return paths;
        } catch (Exception e) {
            throw new IllegalStateException("参考轮廓素材读取失败", e);
        }
    }

    private static Path buildSuccessCheckPath() {
        Path path = new Path();
        path.moveTo(15.5f * 3.875f, 24.5f * 3.875f);
        path.lineTo(21.5f * 3.875f, 30.5f * 3.875f);
        path.lineTo(32.5f * 3.875f, 17.5f * 3.875f);
        return path;
    }

    private static Path parsePolygon(String data) {
        Path path = new Path();
        path.setFillType(Path.FillType.EVEN_ODD);
        int index = 0;
        char command = 0;
        while (index < data.length()) {
            char value = data.charAt(index);
            if (value == 'M' || value == 'L' || value == 'Z') {
                command = value;
                index++;
                if (command == 'Z') {
                    path.close();
                }
            } else if (value == ',' || Character.isWhitespace(value)) {
                index++;
            } else {
                if (command != 'M' && command != 'L') {
                    throw new IllegalArgumentException("ref-00 路径命令损坏");
                }
                int xEnd = numberEnd(data, index);
                float x = Float.parseFloat(data.substring(index, xEnd));
                int index2 = skipSeparators(data, xEnd);
                int yEnd = numberEnd(data, index2);
                float y = Float.parseFloat(data.substring(index2, yEnd));
                index = yEnd;
                if (command == 'M') {
                    path.moveTo(x, y);
                    command = 'L';
                } else {
                    path.lineTo(x, y);
                }
            }
        }
        return path;
    }

    private static int numberEnd(String data, int start) {
        int index = start;
        while (index < data.length()) {
            char value = data.charAt(index);
            if (value == ',' || Character.isWhitespace(value) || Character.isLetter(value)) {
                break;
            }
            index++;
        }
        if (index == start) {
            throw new IllegalArgumentException("ref-00 坐标缺失");
        }
        return index;
    }

    private static int skipSeparators(String data, int start) {
        int index = start;
        while (index < data.length() && (data.charAt(index) == ',' || Character.isWhitespace(data.charAt(index)))) {
            index++;
        }
        return index;
    }

    private static Bitmap decodePng(Context context, String name) {
        try (InputStream in = FaceIslandAssets.open(context, name)) {
            Bitmap bitmap = BitmapFactory.decodeStream(in);
            if (bitmap == null || bitmap.getWidth() != SOURCE_WIDTH || bitmap.getHeight() != SOURCE_WIDTH) {
                throw new IllegalStateException("内嵌超级岛素材解码失败: " + name);
            }
            bitmap.setDensity(0);
            return bitmap;
        } catch (Exception e) {
            throw new IllegalStateException("内嵌超级岛素材读取失败: " + name, e);
        }
    }

    private static float clamp(float value) {
        if (value < 0.0f) {
            return 0.0f;
        }
        if (value > 1.0f) {
            return 1.0f;
        }
        return value;
    }
}
