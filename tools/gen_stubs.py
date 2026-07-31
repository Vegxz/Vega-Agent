#!/usr/bin/env python3
"""Generates a minimal android.jar-style stub source tree, and enforces an API floor.

Only the API surface this app actually touches is declared. Every method body
throws, exactly like the real android.jar — the jar is a *compile-time* contract,
never executed on device.

The spec is validated by compiling the ORIGINAL JAVA sources against it: the
Java is known to compile against the real SDK, so if it also compiles against
these stubs, the stubs faithfully model the surface the app uses.

THE API FLOOR
-------------
A signature-only stub set has one blind spot, and it shipped a real bug: an
android.jar (real or generated) says whether a member EXISTS, never which
Android release added it. `Service.stopForeground(int)` and its
`STOP_FOREGROUND_REMOVE` flag arrived in API 24; the app's floor is 23. That
call compiled clean against every jar in the build, passed every source
contract, and raised NoSuchMethodError on Android 6 — on the single code path
every finished run goes through.

So a member may now carry the level it was introduced at:

    '@since24 public static final int STOP_FOREGROUND_REMOVE = 1',

For every tagged member whose level exceeds MIN_SDK, this script scans `src/`
for uses of that symbol and FAILS unless each one sits inside an explicit
`Build.VERSION.SDK_INT` decision. The tag is also emitted as a comment above the
generated member, so the stub tree itself records the level.

WHAT IT CATCHES
    - A post-floor member used with NO version check anywhere near it. This is
      the entire class of bug above, and it is by far the common one: nobody
      writes half a guard.
    - The same, in any file under src/, including code added later — the scan is
      over the source tree, not over a list.
    - A member whose only guard is at the WRONG level (`>= 21` around an API 26
      call). Region levels are compared numerically.
    - MIN_SDK drifting away from the build: it is asserted against
      build.gradle.kts, AndroidManifest.xml and mkapk.sh's aapt2/d8/apksigner
      flags, all five of which disagreed before.

WHAT IT DOES NOT CATCH — read this before trusting a clean run
    - Untagged members. Coverage is the tag table, not the whole SDK. Tags are
      added as the surface grows; an untagged post-floor member is invisible
      here exactly as it was before.
    - WHICH SIDE of the guard a call is on. The check proves an SDK_INT decision
      surrounds the call, not that the call sits in the correct branch. This is
      deliberate: `if (SDK_INT >= 24) stopForeground(FLAG) else stopForeground(true)`
      must pass, and both branches are inside one API-level decision. A call put
      in the wrong branch is caught by review, not by this.
    - OVERLOADS. Symbols are matched by NAME, so a tag on `stopForeground(int)`
      would also flag `stopForeground(boolean)`. Where overloads differ by level,
      tag a constant that only the newer one takes (STOP_FOREGROUND_REMOVE) or
      leave the member untagged and note it.
    - Shadowing. A same-named member on an app class (`fun getInsets()` on some
      Vega type) reads as a platform call and would be a false positive.
    - Runtime reachability. A guarded call inside a lambda that is stored and
      invoked elsewhere is still counted as guarded.
    - Anything outside `src/` — `tests/` is not scanned.

Usage:  gen_stubs.py [outdir] [--no-api-check] [--warn-only]
"""
import os
import re
import shutil
import sys

_ARGS = [a for a in sys.argv[1:] if not a.startswith('--')]
_FLAGS = {a for a in sys.argv[1:] if a.startswith('--')}

OUT = _ARGS[0] if _ARGS else 'tc/stubs-src'
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, 'src')

# The app's API floor. Must equal build.gradle.kts `minSdk`, AndroidManifest's
# <uses-sdk android:minSdkVersion>, and mkapk.sh's --min-sdk-version / --min-api
# / apksigner --min-sdk-version. verify_min_sdk() below asserts exactly that,
# because those five drifted apart once already and that is what hid the bug.
MIN_SDK = 23

# fqcn -> (kind, extends, implements, [members])
#   kind: 'class' | 'abstract' | 'interface' | 'enum'
#   member strings are literal Java declarations; a trailing '=' value makes a
#   field, otherwise a method (body auto-filled). A member may be prefixed with
#   '@since<level> ' to record the API level it was introduced at.
S = {}

# symbol name -> API level, harvested from the @since tags while rendering.
SINCE = {}

SINCE_TAG = re.compile(r'^@since(\d+)\s+')

def _symbols(decl):
    """Every spelling a tagged declaration can have at a Kotlin call site.

    The Java name, plus the Kotlin property form where one exists: `ctx.dataDir`
    for `getDataDir()`, `view.forceDarkAllowed = x` for
    `setForceDarkAllowed(boolean)`. Only a ZERO-arg getter and a ONE-arg setter
    become properties — `getInsets(int)` stays a call, and deriving `insets`
    from it would have matched every `insets` local in the inset watcher. An
    `isFoo()` getter keeps its name as the property, so it needs no second
    spelling.
    """
    hit = re.search(r'\b(\w+)\s*\((.*)\)', decl)
    if not hit:
        # field: 'public static final int FOO = 1' / 'public static String BAR = ""'
        return {decl.split('=')[0].strip().split()[-1]}
    name, params = hit.group(1), hit.group(2).strip()
    out = {name}
    getter = re.match(r'^get([A-Z]\w*)$', name)
    setter = re.match(r'^set([A-Z]\w*)$', name)
    if getter and not params:
        out.add(getter.group(1)[0].lower() + getter.group(1)[1:])
    elif setter and params and ',' not in params:
        out.add(setter.group(1)[0].lower() + setter.group(1)[1:])
    return out


def c(fq, members, extends=None, implements=None, kind='class', generics=''):
    S[fq] = (kind, extends, implements, members, generics)


# ---------------------------------------------------------------- android.util
c('android.util.TypedValue', [
    'public static final int COMPLEX_UNIT_DIP = 1',
    'public static final int COMPLEX_UNIT_SP = 2',
    'public static float applyDimension(int unit, float value, android.util.DisplayMetrics metrics)',
])
c('android.util.DisplayMetrics', [
    'public int widthPixels',
    'public int heightPixels',
    'public float density',
])
c('android.util.Base64', [
    'public static final int DEFAULT = 0',
    'public static final int NO_WRAP = 2',
    'public static String encodeToString(byte[] input, int flags) {'
    ' return java.util.Base64.getEncoder().encodeToString(input); }',
    'public static byte[] decode(String str, int flags) {'
    ' return java.util.Base64.getDecoder().decode(str); }',
])

# ---------------------------------------------------------------- android.os
c('android.os.Build', [
    'public static class VERSION { public static int SDK_INT; }',
    # Read by App.OEM_AUTOSTART and nowhere else — see the comment there.
    'public static String MANUFACTURER = ""',
])
c('android.os.Bundle', [
    'public void putString(String key, String value)',
    'public String getString(String key)',
])
c('android.os.SystemClock', [
    'public static long uptimeMillis() { return System.nanoTime() / 1000000L; }',
])
c('android.os.IBinder', [], kind='interface')
c('android.os.Looper', ['public static android.os.Looper getMainLooper()'])
c('android.os.Handler', [
    'public Handler(android.os.Looper looper)',
    'public boolean post(Runnable r)',
    'public boolean postDelayed(Runnable r, long delayMillis)',
    'public void removeCallbacks(Runnable r)',
    'public void removeCallbacksAndMessages(Object token)',
])
c('android.os.PowerManager', [
    'public static final int PARTIAL_WAKE_LOCK = 1',
    'public android.os.PowerManager.WakeLock newWakeLock(int levelAndFlags, String tag)',
    'public boolean isIgnoringBatteryOptimizations(String packageName)',
    'public class WakeLock { public void acquire(long timeout) { throw new RuntimeException("Stub!"); }'
    ' public void release() { throw new RuntimeException("Stub!"); }'
    ' public boolean isHeld() { throw new RuntimeException("Stub!"); }'
    ' public void setReferenceCounted(boolean value) { throw new RuntimeException("Stub!"); } }',
])
c('android.os.Environment', [
    'public static final String DIRECTORY_DOWNLOADS = "Download"',
    'public static java.io.File getExternalStorageDirectory()',
    'public static java.io.File getExternalStoragePublicDirectory(String type)',
    '@since30 public static boolean isExternalStorageManager()',
])

# ---------------------------------------------------------------- android.net
c('android.net.Uri', ['public static android.net.Uri parse(String uriString)'])

# ---------------------------------------------------------------- android.graphics
c('android.graphics.Rect', [
    'public int left', 'public int top', 'public int right', 'public int bottom',
    'public int width()', 'public int height()',
])
c('android.graphics.RectF', [
    'public float left', 'public float top', 'public float right', 'public float bottom',
    'public RectF(float left, float top, float right, float bottom) {'
    ' this.left = left; this.top = top; this.right = right; this.bottom = bottom; }',
    'public String describe() { return String.format(java.util.Locale.US,'
    ' "%.4f/%.4f/%.4f/%.4f", left, top, right, bottom); }',
])
c('android.graphics.PixelFormat', ['public static final int TRANSLUCENT = -3'])
c('android.graphics.ColorFilter', [])
c('android.graphics.PorterDuff', [
    'public enum Mode { SRC_IN, SRC_OVER, MULTIPLY }',
])
c('android.graphics.PorterDuffColorFilter',
  ['public PorterDuffColorFilter(int color, android.graphics.PorterDuff.Mode mode)'],
  extends='android.graphics.ColorFilter')
c('android.graphics.Shader', [
    'public enum TileMode { CLAMP, REPEAT, MIRROR }',
])
c('android.graphics.LinearGradient', [
    'public LinearGradient(float x0, float y0, float x1, float y1, int[] colors,'
    ' float[] positions, android.graphics.Shader.TileMode tile)',
], extends='android.graphics.Shader')
c('android.graphics.Typeface', [
    'public static final int NORMAL = 0',
    'public static final int ITALIC = 2',
    'public static final android.graphics.Typeface SANS_SERIF = null',
    'public static final android.graphics.Typeface MONOSPACE = null',
    'public static final android.graphics.Typeface DEFAULT_BOLD = null',
    'public static android.graphics.Typeface createFromAsset(android.content.res.AssetManager mgr, String path)',
])
c('android.graphics.Path', [
    'private final StringBuilder ops = new StringBuilder()',
    'private android.graphics.Path.FillType fill = android.graphics.Path.FillType.WINDING',
    'private static String f(float v) { return String.format(java.util.Locale.US, "%.4f", v); }',
    'public String recordedOps() { return ops.toString(); }',
    'public void reset() { ops.setLength(0);'
    ' fill = android.graphics.Path.FillType.WINDING; }',
    'public enum FillType { WINDING, EVEN_ODD, INVERSE_WINDING, INVERSE_EVEN_ODD }',
    'public void setFillType(android.graphics.Path.FillType type) { fill = type;'
    ' ops.append("F ").append(type.name()).append("\\n"); }',
    'public android.graphics.Path.FillType getFillType() { return fill; }',
    'public void moveTo(float x, float y) { ops.append("M ").append(f(x)).append(" ")'
    '.append(f(y)).append("\\n"); }',
    'public void lineTo(float x, float y) { ops.append("L ").append(f(x)).append(" ")'
    '.append(f(y)).append("\\n"); }',
    'public void quadTo(float x1, float y1, float x2, float y2) { ops.append("Q ")'
    '.append(f(x1)).append(" ").append(f(y1)).append(" ").append(f(x2)).append(" ")'
    '.append(f(y2)).append("\\n"); }',
    'public void cubicTo(float x1, float y1, float x2, float y2, float x3, float y3) {'
    ' ops.append("C ").append(f(x1)).append(" ").append(f(y1)).append(" ").append(f(x2))'
    '.append(" ").append(f(y2)).append(" ").append(f(x3)).append(" ").append(f(y3))'
    '.append("\\n"); }',
    'public void arcTo(android.graphics.RectF oval, float startAngle, float sweepAngle) {'
    ' ops.append("A ").append(oval.describe()).append(" ").append(f(startAngle)).append(" ")'
    '.append(f(sweepAngle)).append("\\n"); }',
    'public void close() { ops.append("Z\\n"); }',
])
c('android.graphics.Paint', [
    'public static final int ANTI_ALIAS_FLAG = 1',
    'public Paint()',
    'public Paint(int flags)',
    'public void setColor(int color)',
    'public int getColor()',
    'public void setAlpha(int a)',
    'public int getAlpha()',
    'public void setStyle(android.graphics.Paint.Style style)',
    'public void setStrokeCap(android.graphics.Paint.Cap cap)',
    'public void setStrokeJoin(android.graphics.Paint.Join join)',
    'public void setStrokeWidth(float width)',
    'public float getStrokeWidth()',
    'public void setColorFilter(android.graphics.ColorFilter filter)',
    'public android.graphics.ColorFilter getColorFilter()',
    'public android.graphics.Shader setShader(android.graphics.Shader shader)',
    'public android.graphics.Shader getShader()',
    'public float measureText(String text)',
    'public void setShadowLayer(float radius, float dx, float dy, int shadowColor)',
    'public void clearShadowLayer()',
    'public void setTypeface(android.graphics.Typeface typeface)',
    'public void setTextAlign(android.graphics.Paint.Align align)',
    'public void setTextSize(float size)',
    'public float getTextSize()',
    'public float descent()',
    'public float ascent()',
    'public enum Style { FILL, STROKE, FILL_AND_STROKE }',
    'public enum Cap { BUTT, ROUND, SQUARE }',
    'public enum Join { MITER, ROUND, BEVEL }',
    'public enum Align { LEFT, CENTER, RIGHT }',
])
c('android.graphics.Canvas', [
    'public int save()',
    'public void restoreToCount(int saveCount)',
    'public void translate(float dx, float dy)',
    'public void scale(float sx, float sy)',
    'public void drawPath(android.graphics.Path path, android.graphics.Paint paint)',
    'public void drawCircle(float cx, float cy, float radius,'
    ' android.graphics.Paint paint)',
    'public void drawText(String text, float x, float y, android.graphics.Paint paint)',
    'public void drawArc(float left, float top, float right, float bottom,'
    ' float startAngle, float sweepAngle, boolean useCenter,'
    ' android.graphics.Paint paint)',
])
c('android.graphics.Bitmap', [])
c('android.graphics.BitmapFactory', [
    'public static android.graphics.Bitmap decodeFile(String pathName, android.graphics.BitmapFactory.Options opts)',
    'public static class Options { public boolean inJustDecodeBounds;'
    ' public int inSampleSize; public int outWidth; public int outHeight;'
    ' public Options() { } }',
])
c('android.view.Choreographer', [
    'public static android.view.Choreographer getInstance()',
    'public void postFrameCallback(android.view.Choreographer.FrameCallback callback)',
    'public void removeFrameCallback(android.view.Choreographer.FrameCallback callback)',
    'public interface FrameCallback { public void doFrame(long frameTimeNanos); }',
])
c('android.graphics.Outline', [
    'public void setRoundRect(int left, int top, int right, int bottom, float radius)',
])

# ------------------------------------------------- android.graphics.drawable
c('android.graphics.drawable.Drawable', [
    'public abstract void draw(android.graphics.Canvas canvas)',
    'public abstract void setAlpha(int alpha)',
    'public abstract void setColorFilter(android.graphics.ColorFilter colorFilter)',
    'public abstract int getOpacity()',
    'public android.graphics.Rect getBounds()',
    'public void setBounds(int left, int top, int right, int bottom)',
    'public void invalidateSelf()',
    'public int getIntrinsicWidth()',
    'public int getIntrinsicHeight()',
], kind='abstract')
c('android.graphics.drawable.ColorDrawable', [
    'public ColorDrawable(int color)',
    'public void draw(android.graphics.Canvas canvas)',
    'public void setAlpha(int alpha)',
    'public void setColorFilter(android.graphics.ColorFilter colorFilter)',
    'public int getOpacity()',
], extends='android.graphics.drawable.Drawable')
c('android.graphics.drawable.GradientDrawable', [
    'public static final int OVAL = 1',
    'public static final int RADIAL_GRADIENT = 1',
    'public GradientDrawable()',
    'public GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation orientation, int[] colors)',
    'public void setColor(int argb)',
    'public void setCornerRadius(float radius)',
    'public void setCornerRadii(float[] radii)',
    'public void setStroke(int width, int color)',
    'public void setShape(int shape)',
    'public void setSize(int width, int height)',
    'public void setGradientType(int gradient)',
    'public void setColors(int[] colors)',
    'public void setGradientRadius(float gradientRadius)',
    'public void setGradientCenter(float x, float y)',
    'public enum Orientation { TOP_BOTTOM, TR_BL, RIGHT_LEFT, BR_TL, BOTTOM_TOP, BL_TR, LEFT_RIGHT, TL_BR }',
    'public void draw(android.graphics.Canvas canvas)',
    'public void setAlpha(int alpha)',
    'public void setColorFilter(android.graphics.ColorFilter colorFilter)',
    'public int getOpacity()',
], extends='android.graphics.drawable.Drawable')
c('android.graphics.drawable.LayerDrawable', [
    'public LayerDrawable(android.graphics.drawable.Drawable[] layers)',
    'public void setLayerInset(int index, int l, int t, int r, int b)',
    'public void draw(android.graphics.Canvas canvas)',
    'public void setAlpha(int alpha)',
    'public void setColorFilter(android.graphics.ColorFilter colorFilter)',
    'public int getOpacity()',
], extends='android.graphics.drawable.Drawable')
c('android.graphics.drawable.RippleDrawable', [
    'public RippleDrawable(android.content.res.ColorStateList color,'
    ' android.graphics.drawable.Drawable content, android.graphics.drawable.Drawable mask)',
], extends='android.graphics.drawable.LayerDrawable')

# ---------------------------------------------------------------- android.content
c('android.content.res.AssetManager', [])
c('android.content.res.ColorStateList', [
    'public ColorStateList(int[][] states, int[] colors)',
    'public static android.content.res.ColorStateList valueOf(int color)',
])
c('android.content.res.Configuration', [
    'public static final int UI_MODE_NIGHT_MASK = 48',
    'public static final int UI_MODE_NIGHT_YES = 32',
    'public static final int UI_MODE_NIGHT_NO = 16',
    'public int uiMode',
])
c('android.content.res.Resources', [
    'public android.util.DisplayMetrics getDisplayMetrics()',
    'public android.content.res.Configuration getConfiguration()',
])
# The FULL public surface, not just the members the app calls.
#
# AgentLoopTests implements this interface to run the real agent loop with an
# in-memory store, and it has to compile against BOTH this stub set and a real
# android.jar. An implementation of the real interface fails here if a member is
# missing (nothing to override), and an implementation of a subset fails there
# (abstract member not implemented) — so the two surfaces have to agree exactly.
c('android.content.SharedPreferences', [
    'public java.util.Map<String, ?> getAll()',
    'public String getString(String key, String defValue)',
    'public java.util.Set<String> getStringSet(String key, java.util.Set<String> defValues)',
    'public int getInt(String key, int defValue)',
    'public long getLong(String key, long defValue)',
    'public float getFloat(String key, float defValue)',
    'public boolean getBoolean(String key, boolean defValue)',
    'public boolean contains(String key)',
    'public android.content.SharedPreferences.Editor edit()',
    'public void registerOnSharedPreferenceChangeListener('
    'android.content.SharedPreferences.OnSharedPreferenceChangeListener listener)',
    'public void unregisterOnSharedPreferenceChangeListener('
    'android.content.SharedPreferences.OnSharedPreferenceChangeListener listener)',
    'public interface OnSharedPreferenceChangeListener {'
    ' void onSharedPreferenceChanged('
    'android.content.SharedPreferences sharedPreferences, String key); }',
    'public interface Editor {'
    ' android.content.SharedPreferences.Editor putString(String key, String value);'
    ' android.content.SharedPreferences.Editor putStringSet('
    'String key, java.util.Set<String> values);'
    ' android.content.SharedPreferences.Editor putInt(String key, int value);'
    ' android.content.SharedPreferences.Editor putLong(String key, long value);'
    ' android.content.SharedPreferences.Editor putFloat(String key, float value);'
    ' android.content.SharedPreferences.Editor putBoolean(String key, boolean value);'
    ' android.content.SharedPreferences.Editor remove(String key);'
    ' android.content.SharedPreferences.Editor clear();'
    ' void apply(); boolean commit(); }',
], kind='interface')
c('android.content.ContentValues', [
    'public ContentValues()',
    'public void put(String key, String value)',
    'public void put(String key, Integer value)',
    'public void put(String key, Long value)',
    'public void clear()',
])
c('android.content.ClipData', [
    'public static android.content.ClipData newPlainText(CharSequence label, CharSequence text)',
    'public int getItemCount()',
    'public android.content.ClipData.Item getItemAt(int index)',
    'public static class Item { public android.net.Uri getUri() { throw new RuntimeException("Stub!"); } }',
])
c('android.content.ClipboardManager', [
    'public void setPrimaryClip(android.content.ClipData clip)',
])
c('android.content.DialogInterface', [
    'public interface OnDismissListener { void onDismiss(android.content.DialogInterface dialog); }',
], kind='interface')
# Opaque on purpose: App.resolves() only asks whether it is null, so modelling
# activityInfo would add surface the app does not touch.
c('android.content.pm.ResolveInfo', [])
c('android.content.pm.PackageManager', [
    'public static final int PERMISSION_GRANTED = 0',
    'public static final int MATCH_DEFAULT_ONLY = 65536',
    'public android.content.Intent getLaunchIntentForPackage(String packageName)',
    'public android.content.pm.ResolveInfo resolveActivity('
    'android.content.Intent intent, int flags)',
])
c('android.content.pm.ServiceInfo',
  ['@since29 public static final int FOREGROUND_SERVICE_TYPE_DATA_SYNC = 1'])
c('android.content.ContentResolver', [
    'public java.io.InputStream openInputStream(android.net.Uri uri)',
    'public java.io.OutputStream openOutputStream(android.net.Uri uri)',
    'public String getType(android.net.Uri uri)',
    'public android.database.Cursor query(android.net.Uri uri, String[] projection,'
    ' String selection, String[] selectionArgs, String sortOrder)',
    'public android.net.Uri insert(android.net.Uri url, android.content.ContentValues values)',
    'public int update(android.net.Uri uri, android.content.ContentValues values,'
    ' String where, String[] selectionArgs)',
    'public int delete(android.net.Uri url, String where, String[] selectionArgs)',
])
c('android.content.Intent', [
    'public static final String ACTION_OPEN_DOCUMENT = "android.intent.action.OPEN_DOCUMENT"',
    'public static final String CATEGORY_OPENABLE = "android.intent.category.OPENABLE"',
    'public static final String EXTRA_ALLOW_MULTIPLE = "android.intent.extra.ALLOW_MULTIPLE"',
    'public static final int FLAG_ACTIVITY_NEW_TASK = 268435456',
    'public static final int FLAG_ACTIVITY_SINGLE_TOP = 536870912',
    'public static final int FLAG_ACTIVITY_CLEAR_TOP = 67108864',
    'public static final int FLAG_ACTIVITY_CLEAR_TASK = 32768',
    'public static final String ACTION_VIEW = "android.intent.action.VIEW"',
    'public android.content.Intent addFlags(int flags)',
    'public Intent()',
    'public Intent(String action)',
    'public Intent(String action, android.net.Uri uri)',
    'public Intent(android.content.Context packageContext, Class<?> cls)',
    'public android.content.Intent setAction(String action)',
    'public String getAction()',
    'public android.content.Intent addCategory(String category)',
    'public android.content.Intent setType(String type)',
    'public android.content.Intent setClassName(String packageName, String className)',
    'public android.content.Intent setFlags(int flags)',
    'public int getFlags()',
    'public android.content.Intent putExtra(String name, String value)',
    'public android.content.Intent putExtra(String name, long value)',
    'public android.content.Intent putExtra(String name, boolean value)',
    'public String getStringExtra(String name)',
    'public long getLongExtra(String name, long defaultValue)',
    'public android.content.ClipData getClipData()',
    'public android.net.Uri getData()',
    'public static android.content.Intent createChooser(android.content.Intent target, CharSequence title)',
])
c('android.content.Context', [
    # A constructor with a REAL (empty) body. The generated default throws, which
    # makes the class impossible to subclass at runtime — and subclassing it is how a
    # test supplies the app with somewhere to keep preferences and files.
    'public Context() { }',
    'public static final int MODE_PRIVATE = 0',
    'public static final String NOTIFICATION_SERVICE = "notification"',
    'public static final String POWER_SERVICE = "power"',
    'public static final String CLIPBOARD_SERVICE = "clipboard"',
    'public static final String INPUT_METHOD_SERVICE = "input_method"',
    'public static final String ACTIVITY_SERVICE = "activity"',
    'public static final String CONNECTIVITY_SERVICE = "connectivity"',
    'public static final String ALARM_SERVICE = "alarm"',
    'public android.content.pm.PackageManager getPackageManager()',
    'public android.content.Context getApplicationContext()',
    'public android.content.res.Resources getResources()',
    'public android.content.res.AssetManager getAssets()',
    'public android.content.SharedPreferences getSharedPreferences(String name, int mode)',
    'public java.io.File getFilesDir()',
    'public java.io.File getCacheDir()',
    '@since24 public java.io.File getDataDir()',
    'public java.io.File getExternalFilesDir(String type)',
    'public String getPackageName()',
    'public Object getSystemService(String name)',
    'public <T> T getSystemService(Class<T> serviceClass)',
    'public android.content.ContentResolver getContentResolver()',
    'public void startActivity(android.content.Intent intent)',
    'public void startService(android.content.Intent service)',
    '@since26 public void startForegroundService(android.content.Intent service)',
    'public int checkSelfPermission(String permission)',
], kind='abstract')

# Concrete, and that is the point: subclassing abstract Context means implementing
# its entire surface against a real android.jar (about a hundred abstract methods),
# whereas ContextWrapper is concrete in both worlds, so a test can override the six
# members it needs and leave the rest to throw — which is the honest behaviour for a
# platform call nobody modelled.
# BEHAVIOURAL, like Base64 and TextUtils below, and for the same reason: a test has
# to be able to construct one.
#
# A real android.jar cannot help here — it is signature-only, so every one of its
# methods throws RuntimeException("Stub!") at runtime, which is why the loop tests run
# against this stub set instead. ContextWrapper is concrete in both worlds so a test
# can subclass it and override just the members it needs.
c('android.content.ContextWrapper', [
    'private android.content.Context base;',
    'public ContextWrapper(android.content.Context base) { this.base = base; }',
    'public android.content.Context getBaseContext() { return this.base; }',
    'public android.content.Context getApplicationContext() { return this.base; }',
], extends='android.content.Context')

# ---------------------------------------------------------------- android.database
c('android.database.Cursor', [
    'public boolean moveToFirst()',
    'public int getColumnIndex(String columnName)',
    'public String getString(int columnIndex)',
    'public void close()',
], kind='interface')

# ---------------------------------------------------------------- android.provider
c('android.provider.MediaStore', [
    'public static class MediaColumns { public static final String DISPLAY_NAME = "_display_name";'
    ' public static final String MIME_TYPE = "mime_type";'
    ' public static final String RELATIVE_PATH = "relative_path";'
    ' public static final String IS_PENDING = "is_pending"; }',
    'public static class Downloads { public static final android.net.Uri EXTERNAL_CONTENT_URI = null; }',
])
c('android.provider.Settings', [
    'public static final String ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS ='
    ' "android.settings.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS"',
    'public static final String ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS ='
    ' "android.settings.IGNORE_BATTERY_OPTIMIZATION_SETTINGS"',
    'public static final String ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION ='
    ' "android.settings.MANAGE_APP_ALL_FILES_ACCESS_PERMISSION"',
    'public static final String ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION ='
    ' "android.settings.MANAGE_ALL_FILES_ACCESS_PERMISSION"',
])

# ---------------------------------------------------------------- android.security
c('android.security.keystore.KeyProperties', [
    'public static final String KEY_ALGORITHM_AES = "AES"',
    'public static final String BLOCK_MODE_GCM = "GCM"',
    'public static final String ENCRYPTION_PADDING_NONE = "NoPadding"',
    'public static final int PURPOSE_ENCRYPT = 1',
    'public static final int PURPOSE_DECRYPT = 2',
])
c('android.security.keystore.KeyGenParameterSpec', [
    'public static final class Builder {'
    ' public Builder(String keystoreAlias, int purposes) { }'
    ' public android.security.keystore.KeyGenParameterSpec.Builder setBlockModes(String... blockModes)'
    ' { throw new RuntimeException("Stub!"); }'
    ' public android.security.keystore.KeyGenParameterSpec.Builder setEncryptionPaddings(String... paddings)'
    ' { throw new RuntimeException("Stub!"); }'
    ' public android.security.keystore.KeyGenParameterSpec.Builder setKeySize(int keySize)'
    ' { throw new RuntimeException("Stub!"); }'
    ' public android.security.keystore.KeyGenParameterSpec build()'
    ' { throw new RuntimeException("Stub!"); } }',
], implements='java.security.spec.AlgorithmParameterSpec')

# ---------------------------------------------------------------- android.text
c('android.text.Editable', [
    'public android.text.Editable insert(int where, CharSequence text)',
    'public android.text.Editable delete(int start, int end)',
    'public int length()',
], kind='interface', implements='CharSequence, android.text.Spannable')
c('android.text.Spanned', [
    'public static final int SPAN_EXCLUSIVE_EXCLUSIVE = 33',
    '<T> T[] getSpans(int start, int end, Class<T> type);',
], kind='interface', implements='CharSequence')
c('android.text.Spannable', [
    'public void setSpan(Object what, int start, int end, int flags);',
    'public void removeSpan(Object what);',
], kind='interface', implements='android.text.Spanned')
c('android.text.SpannableStringBuilder', [
    'public SpannableStringBuilder(CharSequence text)',
    'public android.text.SpannableStringBuilder append(CharSequence text)',
    'public int length()',
    'public char charAt(int index)',
    'public CharSequence subSequence(int start, int end)',
    'public String toString()',
    'public android.text.Editable insert(int where, CharSequence text)',
    'public android.text.Editable delete(int start, int end)',
    'public void setSpan(Object what, int start, int end, int flags)',
    'public void removeSpan(Object what)',
    'public <T> T[] getSpans(int start, int end, Class<T> type)',
], implements='CharSequence, android.text.Editable')
c('android.text.Selection', [
    'public static void setSelection(android.text.Spannable text, int start, int stop)',
])
c('android.text.Html', [
    'public static final int FROM_HTML_MODE_LEGACY = 0',
    '@since24 public static android.text.Spanned fromHtml(String source, int flags)',
])
c('android.text.TextPaint', [
    'public int bgColor',
    'public int baselineShift',
    'public int linkColor',
    'public float density',
], extends='android.graphics.Paint')
c('android.text.style.UpdateAppearance', [], kind='interface')
c('android.text.style.CharacterStyle', [
    'public abstract void updateDrawState(android.text.TextPaint tp);',
], kind='abstract')
c('android.text.InputType', [
    'public static final int TYPE_CLASS_TEXT = 1',
    'public static final int TYPE_CLASS_NUMBER = 2',
    'public static final int TYPE_TEXT_VARIATION_URI = 16',
    'public static final int TYPE_TEXT_VARIATION_PASSWORD = 128',
    'public static final int TYPE_TEXT_VARIATION_VISIBLE_PASSWORD = 144',
    'public static final int TYPE_TEXT_FLAG_CAP_SENTENCES = 16384',
    'public static final int TYPE_TEXT_FLAG_MULTI_LINE = 131072',
])
c('android.text.TextUtils', [
    'public static boolean isEmpty(CharSequence str) {'
    ' return str == null || str.length() == 0; }',
    'public enum TruncateAt { START, MIDDLE, END, MARQUEE }',
])
c('android.text.TextWatcher', [
    'void beforeTextChanged(CharSequence s, int start, int count, int after);',
    'void onTextChanged(CharSequence s, int start, int before, int count);',
    'void afterTextChanged(android.text.Editable s);',
], kind='interface')
c('android.text.method.MovementMethod', [], kind='interface')
c('android.text.method.LinkMovementMethod', [
    'public static android.text.method.MovementMethod getInstance()',
], implements='android.text.method.MovementMethod')

# ---------------------------------------------------------------- android.view
c('android.view.Gravity', [
    'public static final int NO_GRAVITY = 0',
    'public static final int CENTER_HORIZONTAL = 1',
    'public static final int CENTER_VERTICAL = 16',
    'public static final int CENTER = 17',
    'public static final int TOP = 48',
    'public static final int BOTTOM = 80',
    'public static final int LEFT = 3',
    'public static final int RIGHT = 5',
    'public static final int START = 8388611',
    'public static final int END = 8388613',
])
c('android.view.HapticFeedbackConstants', [
    'public static final int VIRTUAL_KEY = 1',
    'public static final int FLAG_IGNORE_GLOBAL_SETTING = 2',
    'public static final int CLOCK_TICK = 4',
])
c('android.view.MotionEvent', [
    'public static final int ACTION_DOWN = 0',
    'public static final int ACTION_UP = 1',
    'public static final int ACTION_MOVE = 2',
    'public static final int ACTION_CANCEL = 3',
    'public static final int ACTION_OUTSIDE = 4',
    'public static final int ACTION_POINTER_UP = 6',
    'public int getActionMasked()',
    'public float getX()',
    'public float getY()',
    'public float getRawX()',
    'public float getRawY()',
])
c('android.view.ViewOutlineProvider', [
    'public abstract void getOutline(android.view.View view, android.graphics.Outline outline)',
], kind='abstract')
c('android.view.ViewPropertyAnimator', [
    'public android.view.ViewPropertyAnimator alpha(float value)',
    'public android.view.ViewPropertyAnimator scaleX(float value)',
    'public android.view.ViewPropertyAnimator scaleY(float value)',
    'public android.view.ViewPropertyAnimator translationX(float value)',
    'public android.view.ViewPropertyAnimator translationY(float value)',
    'public android.view.ViewPropertyAnimator rotation(float value)',
    'public android.view.ViewPropertyAnimator setDuration(long duration)',
    'public android.view.ViewPropertyAnimator setStartDelay(long delay)',
    'public android.view.ViewPropertyAnimator setInterpolator('
    ' android.view.animation.Interpolator interpolator)',
    'public android.view.ViewPropertyAnimator withEndAction(Runnable runnable)',
    'public void start()',
    'public void cancel()',
])
c('android.view.View', [
    'protected void onDraw(android.graphics.Canvas canvas)',
    'protected void onDetachedFromWindow()',
    'public boolean removeCallbacks(Runnable action)',
    'public boolean isLaidOut()',
    'public void getLocationInWindow(int[] outLocation)',
    'public static final int VISIBLE = 0',
    'public static final int INVISIBLE = 4',
    'public static final int GONE = 8',
    'public static final int LAYOUT_DIRECTION_LTR = 0',
    'public static final int LAYOUT_DIRECTION_RTL = 1',
    'public static final int TEXT_DIRECTION_FIRST_STRONG = 1',
    'public static final int TEXT_DIRECTION_LTR = 3',
    'public static final int TEXT_DIRECTION_RTL = 4',
    'public static final int SYSTEM_UI_FLAG_LIGHT_STATUS_BAR = 8192',
    'public static final int SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR = 16',
    'public static final int FOCUS_DOWN = 130',
    'public static final int TEXT_ALIGNMENT_VIEW_START = 5',
    'public static final int TEXT_ALIGNMENT_CENTER = 4',
    'public boolean hasFocus()',
    'public boolean requestFocus()',
    'public void clearFocus()',
    'public void setFocusableInTouchMode(boolean focusable)',
    'public void setTextAlignment(int textAlignment)',
    'public void setClipChildren(boolean clipChildren)',
    'public void setLayoutTransition(android.animation.LayoutTransition transition)',
    'public void setPivotX(float pivotX)',
    'public void setPivotY(float pivotY)',
    'public boolean isAttachedToWindow()',
    'public void setEnabled(boolean enabled)',
    'public boolean isEnabled()',
    'public int getScrollX()',
    'public void setTag(Object tag)',
    'public Object getTag()',
    'public void setTag(int key, Object tag)',
    'public Object getTag(int key)',
    'public void addOnAttachStateChangeListener('
    ' android.view.View.OnAttachStateChangeListener listener)',
    'public void removeOnAttachStateChangeListener('
    ' android.view.View.OnAttachStateChangeListener listener)',
    'public interface OnAttachStateChangeListener {'
    ' void onViewAttachedToWindow(android.view.View v);'
    ' void onViewDetachedFromWindow(android.view.View v); }',
    'public void addOnLayoutChangeListener('
    ' android.view.View.OnLayoutChangeListener listener)',
    'public void removeOnLayoutChangeListener('
    ' android.view.View.OnLayoutChangeListener listener)',
    'public interface OnLayoutChangeListener { void onLayoutChange('
    ' android.view.View v, int left, int top, int right, int bottom,'
    ' int oldLeft, int oldTop, int oldRight, int oldBottom); }',
    'public View(android.content.Context context)',
    'public android.content.Context getContext()',
    'public void invalidate()',
    'public static final int IMPORTANT_FOR_ACCESSIBILITY_NO = 2',
    'public void setImportantForAccessibility(int mode)',
    'public void setPaddingRelative(int start, int top, int end, int bottom)',
    'public boolean performClick()',
    'public void setBackground(android.graphics.drawable.Drawable background)',
    'public android.graphics.drawable.Drawable getBackground()',
    'public void setBackgroundColor(int color)',
    'public void setForeground(android.graphics.drawable.Drawable foreground)',
    'public void setPadding(int left, int top, int right, int bottom)',
    'public int getPaddingLeft()',
    'public int getPaddingTop()',
    'public int getPaddingRight()',
    'public int getPaddingBottom()',
    'public void setOnApplyWindowInsetsListener(android.view.View.OnApplyWindowInsetsListener l)',
    'public void requestApplyInsets()',
    'public interface OnApplyWindowInsetsListener { android.view.WindowInsets'
    ' onApplyWindowInsets(android.view.View v, android.view.WindowInsets insets); }',
    'public void setLayoutParams(android.view.ViewGroup.LayoutParams params)',
    'public android.view.ViewGroup.LayoutParams getLayoutParams()',
    'public void setVisibility(int visibility)',
    'public int getVisibility()',
    'public void setAlpha(float alpha)',
    'public float getAlpha()',
    'public void setScaleX(float scaleX)',
    'public void setScaleY(float scaleY)',
    'public void setTranslationX(float translationX)',
    'public void setTranslationY(float translationY)',
    'public void setRotation(float rotation)',
    'public void setElevation(float elevation)',
    '@since28 public void setOutlineAmbientShadowColor(int color)',
    '@since28 public void setOutlineSpotShadowColor(int color)',
    'public void setOutlineProvider(android.view.ViewOutlineProvider provider)',
    'public void setClipToOutline(boolean clipToOutline)',
    'public void setClipToPadding(boolean clipToPadding)',
    'public void setLayoutDirection(int layoutDirection)',
    'public void setTextDirection(int textDirection)',
    'public void setMinimumHeight(int minHeight)',
    'public void setFitsSystemWindows(boolean fitSystemWindows)',
    'public void setClickable(boolean clickable)',
    'public void setFocusable(boolean focusable)',
    'public void setContentDescription(CharSequence contentDescription)',
    'public void setOnClickListener(android.view.View.OnClickListener l)',
    'public void setOnTouchListener(android.view.View.OnTouchListener l)',
    'public void setOnFocusChangeListener(android.view.View.OnFocusChangeListener l)',
    'public void setOnScrollChangeListener(android.view.View.OnScrollChangeListener l)',
    'public int getWidth()',
    'public int getHeight()',
    'public int getBottom()',
    'public int getMeasuredHeight()',
    'public int getScrollY()',
    'public void scrollBy(int x, int y)',
    'public boolean onTouchEvent(android.view.MotionEvent event)',
    'public boolean onInterceptTouchEvent(android.view.MotionEvent event)',
    'public void measure(int widthMeasureSpec, int heightMeasureSpec)',
    'public void requestLayout()',
    'public android.view.ViewParent getParent()',
    'public android.view.ViewPropertyAnimator animate()',
    'public boolean post(Runnable action)',
    'public boolean postDelayed(Runnable action, long delayMillis)',
    'public boolean performHapticFeedback(int feedbackConstant)',
    'public boolean performHapticFeedback(int feedbackConstant, int flags)',
    '@since29 public void setForceDarkAllowed(boolean allow)',
    'public void setSystemUiVisibility(int visibility)',
    'public int getSystemUiVisibility()',
    'public android.os.IBinder getWindowToken()',
    'public interface OnClickListener { void onClick(android.view.View v); }',
    'public interface OnTouchListener { boolean onTouch(android.view.View v, android.view.MotionEvent event); }',
    'public interface OnFocusChangeListener { void onFocusChange(android.view.View v, boolean hasFocus); }',
    'public interface OnScrollChangeListener { void onScrollChange(android.view.View v,'
    ' int scrollX, int scrollY, int oldScrollX, int oldScrollY); }',
    'public static class MeasureSpec { public static final int UNSPECIFIED = 0;'
    ' public static final int EXACTLY = 1073741824; public static final int AT_MOST = -2147483648;'
    ' public static int makeMeasureSpec(int size, int mode) { throw new RuntimeException("Stub!"); } }',
], implements='android.view.ViewParent')
c('android.view.ViewParent', [], kind='interface')
c('android.graphics.Insets', [
    'public int left',
    'public int top',
    'public int right',
    'public int bottom',
])
c('android.view.WindowInsets', [
    'public int getSystemWindowInsetTop()',
    'public int getSystemWindowInsetBottom()',
    'public int getSystemWindowInsetLeft()',
    'public int getSystemWindowInsetRight()',
    # The navigation bar alone, excluding the IME. Subtracting it from
    # getSystemWindowInsetBottom() is how MainActivity derives a keyboard HEIGHT
    # on the API levels below 30, which have no WindowInsets.Type.ime().
    'public int getStableInsetBottom()',
    '@since30 public android.graphics.Insets getInsets(int typeMask)',
    'public static final class Type { public static int statusBars() {'
    ' throw new RuntimeException("Stub!"); }'
    ' public static int navigationBars() { throw new RuntimeException("Stub!"); }'
    ' public static int ime() { throw new RuntimeException("Stub!"); }'
    ' public static int systemBars() { throw new RuntimeException("Stub!"); } }',
])
c('android.view.ViewGroup', [
    'public void addView(android.view.View child, int index, android.view.ViewGroup.LayoutParams params)',
    'public ViewGroup(android.content.Context context)',
    'public void addView(android.view.View child)',
    'public void addView(android.view.View child, int index)',
    'public void addView(android.view.View child, android.view.ViewGroup.LayoutParams params)',
    'public void removeView(android.view.View view)',
    'public void removeViewAt(int index)',
    'public void removeAllViews()',
    'public int getChildCount()',
    'public android.view.View getChildAt(int index)',
    'public static class LayoutParams { public static final int MATCH_PARENT = -1;'
    ' public static final int WRAP_CONTENT = -2; public int width; public int height;'
    ' public LayoutParams(int width, int height) { } }',
    'public static class MarginLayoutParams extends android.view.ViewGroup.LayoutParams {'
    ' public int leftMargin; public int topMargin; public int rightMargin; public int bottomMargin;'
    ' public MarginLayoutParams(int width, int height) { super(width, height); }'
    ' public void setMarginStart(int start) { throw new RuntimeException("Stub!"); }'
    ' public void setMarginEnd(int end) { throw new RuntimeException("Stub!"); }'
    ' public int getMarginStart() { throw new RuntimeException("Stub!"); }'
    ' public int getMarginEnd() { throw new RuntimeException("Stub!"); }'
    ' public void setMargins(int left, int top, int right, int bottom)'
    ' { throw new RuntimeException("Stub!"); } }',
], extends='android.view.View', kind='abstract')
c('android.view.Window', [
    'public static final int FEATURE_NO_TITLE = 1',
    'public void setStatusBarColor(int color)',
    'public void setNavigationBarColor(int color)',
    'public void setBackgroundDrawable(android.graphics.drawable.Drawable drawable)',
    'public android.view.View getDecorView()',
    'public void setLayout(int width, int height)',
    'public void setGravity(int gravity)',
    'public android.view.WindowManager.LayoutParams getAttributes()',
    'public void setAttributes(android.view.WindowManager.LayoutParams params)',
    'public void addFlags(int flags)',
], kind='abstract')
c('android.view.WindowManager', [
    'public static class LayoutParams { public static final int FLAG_DIM_BEHIND = 2;'
    ' public float dimAmount; }',
], kind='interface')
c('android.view.Menu', [
    'public static final int NONE = 0',
    'public android.view.MenuItem add(int groupId, int itemId, int order, CharSequence title)',
    'public android.view.MenuItem findItem(int id)',
    'public void clear()',
], kind='interface')
c('android.view.MenuItem', [
    'public static final int SHOW_AS_ACTION_IF_ROOM = 1',
    'public android.view.MenuItem setShowAsAction(int actionEnum)',
    'public android.view.MenuItem setEnabled(boolean enabled)',
    'public int getItemId()',
], kind='interface')
c('android.view.ActionMode', [
    'public interface Callback {'
    ' boolean onCreateActionMode(android.view.ActionMode mode, android.view.Menu menu);'
    ' boolean onPrepareActionMode(android.view.ActionMode mode, android.view.Menu menu);'
    ' boolean onActionItemClicked(android.view.ActionMode mode, android.view.MenuItem item);'
    ' void onDestroyActionMode(android.view.ActionMode mode); }',
], kind='abstract')
c('android.view.animation.Interpolator', [], kind='interface',
  implements='android.animation.TimeInterpolator')
c('android.view.animation.DecelerateInterpolator', [
    'public DecelerateInterpolator(float factor)',
    'public float getInterpolation(float input) { return input; }',
], implements='android.view.animation.Interpolator')
c('android.view.animation.OvershootInterpolator', [
    'public OvershootInterpolator(float tension)',
    'public float getInterpolation(float input) { return input; }',
], implements='android.view.animation.Interpolator')
c('android.view.inputmethod.InputMethodManager', [
    'public boolean hideSoftInputFromWindow(android.os.IBinder windowToken, int flags)',
])

# ---------------------------------------------------------------- android.widget
c('android.widget.LinearLayout', [
    'public static final int HORIZONTAL = 0',
    'public static final int VERTICAL = 1',
    'public LinearLayout(android.content.Context context)',
    'public void setOrientation(int orientation)',
    'public void setGravity(int gravity)',
    'public static class LayoutParams extends android.view.ViewGroup.MarginLayoutParams {'
    ' public int gravity; public float weight;'
    ' public LayoutParams(int width, int height) { super(width, height); }'
    ' public LayoutParams(int width, int height, float weight) { super(width, height); } }',
], extends='android.view.ViewGroup')
c('android.widget.FrameLayout', [
    'public FrameLayout(android.content.Context context)',
    'public static class LayoutParams extends android.view.ViewGroup.MarginLayoutParams {'
    ' public int gravity;'
    ' public LayoutParams(int width, int height) { super(width, height); }'
    ' public LayoutParams(int width, int height, int gravity) { super(width, height); } }',
], extends='android.view.ViewGroup')
c('android.widget.ScrollView', [
    'public ScrollView(android.content.Context context)',
    'public void setFillViewport(boolean fillViewport)',
    'public void setVerticalScrollBarEnabled(boolean value)',
    'public boolean fullScroll(int direction)',
    'public void scrollTo(int x, int y)',
], extends='android.widget.FrameLayout')
c('android.widget.HorizontalScrollView', [
    'public HorizontalScrollView(android.content.Context context)',
    'public void setHorizontalScrollBarEnabled(boolean value)',
], extends='android.widget.FrameLayout')
c('android.widget.TextView', [
    'public void setTypeface(android.graphics.Typeface typeface, int style)',
    'public TextView(android.content.Context context)',
    'public void setText(CharSequence text)',
    'public CharSequence getText()',
    'public void setTextColor(int color)',
    'public void setTextSize(float size)',
    'public void setTypeface(android.graphics.Typeface tf)',
    'public android.graphics.Typeface getTypeface()',
    'public void setLineSpacing(float add, float mult)',
    'public void setLetterSpacing(float letterSpacing)',
    'public void setTextIsSelectable(boolean selectable)',
    'public void setLinkTextColor(int color)',
    'public void setMovementMethod(android.text.method.MovementMethod movement)',
    'public void setGravity(int gravity)',
    'public void setSingleLine(boolean singleLine)',
    'public void setMaxLines(int maxLines)',
    'public void setMinLines(int minLines)',
    'public void setMaxWidth(int maxWidth)',
    'public void setEllipsize(android.text.TextUtils.TruncateAt where)',
    'public void setHint(CharSequence hint)',
    'public void setHintTextColor(int color)',
    'public void setInputType(int type)',
    'public android.graphics.Paint getPaint()',
    'public int getSelectionStart()',
    'public int getSelectionEnd()',
    'public void addTextChangedListener(android.text.TextWatcher watcher)',
    'public void setCustomInsertionActionModeCallback( android.view.ActionMode.Callback actionModeCallback)',
    '@since26 public void setTextClassifier( android.view.textclassifier.TextClassifier textClassifier)',
    'public android.text.Layout getLayout()',
    'public int getTotalPaddingLeft()',
    'public int getTotalPaddingTop()',
    'public void setCustomSelectionActionModeCallback(android.view.ActionMode.Callback callback)',
], extends='android.view.View')
c('android.widget.EditText', [
    'public EditText(android.content.Context context)',
    'public android.text.Editable getText()',
    'public void setSelection(int index)',
], extends='android.widget.TextView')
c('android.widget.ImageView', [
    'public ImageView(android.content.Context context)',
    'public void setImageDrawable(android.graphics.drawable.Drawable drawable)',
    'public void setImageBitmap(android.graphics.Bitmap bm)',
    'public void setScaleType(android.widget.ImageView.ScaleType scaleType)',
    'public void setAdjustViewBounds(boolean adjustViewBounds)',
    'public void setMaxHeight(int maxHeight)',
    'public enum ScaleType { MATRIX, FIT_XY, FIT_START, FIT_CENTER, FIT_END, CENTER,'
    ' CENTER_CROP, CENTER_INSIDE }',
], extends='android.view.View')
c('android.widget.ProgressBar', [
    'public ProgressBar(android.content.Context context)',
    'public void setIndeterminate(boolean indeterminate)',
    'public android.graphics.drawable.Drawable getIndeterminateDrawable()',
    'public void setMax(int max)',
    'public void setProgress(int progress)',
    'public int getProgress()',
    'public void setProgressTintList(android.content.res.ColorStateList tint)',
    'public void setProgressBackgroundTintList(android.content.res.ColorStateList tint)',
], extends='android.view.View')
c('android.widget.SeekBar', [
    'public SeekBar(android.content.Context context)',
    'public void setThumb(android.graphics.drawable.Drawable thumb)',
    'public void setThumbOffset(int thumbOffset)',
    'public void setThumbTintList(android.content.res.ColorStateList tint)',
    'public void setSplitTrack(boolean splitTrack)',
    'public void setOnSeekBarChangeListener(android.widget.SeekBar.OnSeekBarChangeListener l)',
    'public interface OnSeekBarChangeListener {'
    ' void onProgressChanged(android.widget.SeekBar seekBar, int progress, boolean fromUser);'
    ' void onStartTrackingTouch(android.widget.SeekBar seekBar);'
    ' void onStopTrackingTouch(android.widget.SeekBar seekBar); }',
], extends='android.widget.ProgressBar')
c('android.widget.CompoundButton', [
    'public CompoundButton(android.content.Context context)',
    'public void setChecked(boolean checked)',
    'public boolean isChecked()',
    'public void setThumbTintList(android.content.res.ColorStateList tint)',
    'public void setTrackTintList(android.content.res.ColorStateList tint)',
    'public void setOnCheckedChangeListener(android.widget.CompoundButton.OnCheckedChangeListener l)',
    'public interface OnCheckedChangeListener {'
    ' void onCheckedChanged(android.widget.CompoundButton buttonView, boolean isChecked); }',
], extends='android.widget.TextView')
c('android.widget.Switch', [
    'public Switch(android.content.Context context)',
    'public void toggle()',
],
  extends='android.widget.CompoundButton')
c('android.widget.Toast', [
    'public static final int LENGTH_SHORT = 0',
    'public static final int LENGTH_LONG = 1',
    'public static android.widget.Toast makeText(android.content.Context context,'
    ' CharSequence text, int duration)',
    'public void show()',
])

# ---------------------------------------------------------------- android.app
c('android.app.Dialog', [
    'public Dialog(android.content.Context context)',
    'public boolean requestWindowFeature(int featureId)',
    'public void setContentView(android.view.View view)',
    'public android.view.Window getWindow()',
    'public void setCancelable(boolean flag)',
    'public void setCanceledOnTouchOutside(boolean cancel)',
    'public void setOnDismissListener(android.content.DialogInterface.OnDismissListener listener)',
    'public void show()',
    'public void dismiss()',
    'public boolean isShowing()',
], implements='android.content.DialogInterface')
c('android.app.Activity', [
    'public Activity()',
    'public void setTheme(int resid)',
    'protected void onCreate(android.os.Bundle savedInstanceState)',
    'protected void onStart()',
    'protected void onResume()',
    'protected void onPause()',
    'protected void onStop()',
    'protected void onDestroy()',
    'public void onConfigurationChanged(android.content.res.Configuration newConfig)',
    'public void onBackPressed()',
    'protected void onActivityResult(int requestCode, int resultCode, android.content.Intent data)',
    'public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults)',
    'public void setContentView(android.view.View view)',
    'public android.view.Window getWindow()',
    'public void finish()',
    'public void recreate()',
    'public void overridePendingTransition(int enterAnim, int exitAnim)',
    'protected void onSaveInstanceState(android.os.Bundle outState)',
    'public boolean isFinishing()',
    'public boolean isDestroyed()',
    'public android.view.View getCurrentFocus()',
    'public void startActivityForResult(android.content.Intent intent, int requestCode)',
    'public void requestPermissions(String[] permissions, int requestCode)',
    'public static final int RESULT_OK = -1',
    'public static final int RESULT_CANCELED = 0',
], extends='android.content.Context')
c('android.app.Service', [
    'public static final int START_NOT_STICKY = 2',
    'public static final int START_REDELIVER_INTENT = 3',
    # The flag is API 24 and so is the int overload that takes it. Tagging the
    # CONSTANT rather than the method is deliberate: symbols are matched by name,
    # so a tag on stopForeground(int) would also flag the boolean overload that
    # is the correct pre-24 call. The constant appears at exactly the sites that
    # need API 24.
    '@since24 public static final int STOP_FOREGROUND_REMOVE = 1',
    'public Service()',
    'public void onCreate()',
    'public int onStartCommand(android.content.Intent intent, int flags, int startId)',
    'public abstract android.os.IBinder onBind(android.content.Intent intent)',
    'public void onTimeout(int startId)',
    'public void onTimeout(int startId, int fgsType)',
    'public void onDestroy()',
    'public void startForeground(int id, android.app.Notification notification)',
    'public void startForeground(int id, android.app.Notification notification, int foregroundServiceType)',
    'public void stopForeground(int flags)',
    # API 1..n, deprecated at 33 but never removed: the only way to leave the
    # foreground on Android 6, where stopForeground(int) does not exist.
    'public void stopForeground(boolean removeNotification)',
    'public void stopSelf()',
    'public void stopSelf(int startId)',
], extends='android.content.Context', kind='abstract')
c('android.app.Notification', [
    'public static class Builder {'
    ' public Builder(android.content.Context context) { }'
    ' public Builder(android.content.Context context, String channelId) { }'
    ' public android.app.Notification.Builder setContentTitle(CharSequence title)'
    ' { throw new RuntimeException("Stub!"); }'
    ' public android.app.Notification.Builder setContentText(CharSequence text)'
    ' { throw new RuntimeException("Stub!"); }'
    ' public android.app.Notification.Builder setSmallIcon(int icon)'
    ' { throw new RuntimeException("Stub!"); }'
    ' public android.app.Notification.Builder setContentIntent(android.app.PendingIntent intent)'
    ' { throw new RuntimeException("Stub!"); }'
    ' public android.app.Notification.Builder setOngoing(boolean ongoing)'
    ' { throw new RuntimeException("Stub!"); }'
    ' public android.app.Notification.Builder setOnlyAlertOnce(boolean onlyAlertOnce)'
    ' { throw new RuntimeException("Stub!"); }'
    ' public android.app.Notification.Builder addAction(android.app.Notification.Action action)'
    ' { throw new RuntimeException("Stub!"); }'
    ' public android.app.Notification.Builder setColor(int argb)'
    ' { throw new RuntimeException("Stub!"); }'
    ' public android.app.Notification build() { throw new RuntimeException("Stub!"); } }',
    'public static class Action { public static class Builder {'
    ' public Builder(int icon, CharSequence title, android.app.PendingIntent intent) { }'
    ' public android.app.Notification.Action build() { throw new RuntimeException("Stub!"); } } }',
])
c('android.app.NotificationChannel', [
    '@since26 public NotificationChannel(String id, CharSequence name, int importance)',
    'public void setDescription(String description)',
    'public void setShowBadge(boolean showBadge)',
])
c('android.app.NotificationManager', [
    'public static final int IMPORTANCE_LOW = 2',
    'public static final int IMPORTANCE_DEFAULT = 3',
    'public void notify(int id, android.app.Notification notification)',
    '@since26 public void createNotificationChannel(android.app.NotificationChannel channel)',
    'public android.app.NotificationChannel getNotificationChannel(String channelId)',
])
c('android.app.PendingIntent', [
    '@since23 public static final int FLAG_IMMUTABLE = 67108864',
    'public static final int FLAG_ONE_SHOT = 1073741824',
    'public static final int FLAG_UPDATE_CURRENT = 134217728',
    'public static android.app.PendingIntent getActivity(android.content.Context context,'
    ' int requestCode, android.content.Intent intent, int flags)',
    'public static android.app.PendingIntent getService(android.content.Context context,'
    ' int requestCode, android.content.Intent intent, int flags)',
])

# ---------------------------------------------------------------- android.media
c('android.media.MediaMetadataRetriever', [
    'public MediaMetadataRetriever()',
    'public void setDataSource(String path)',
    'public static final int OPTION_CLOSEST_SYNC = 2',
    'public android.graphics.Bitmap getFrameAtTime(long timeUs)',
    '@since27 public android.graphics.Bitmap getScaledFrameAtTime(long timeUs, int option, int width, int height)',
    'public void release()',
])

# ---------------------------------------------------------------- android.animation
c('android.animation.Animator', [], kind='abstract')
c('android.animation.AnimatorListenerAdapter', [
    'public void onAnimationEnd(android.animation.Animator animation)',
    'public void onAnimationStart(android.animation.Animator animation)',
    'public void onAnimationCancel(android.animation.Animator animation)',
    'public void onAnimationRepeat(android.animation.Animator animation)',
])
c('android.animation.ValueAnimator', [
    'public static final int INFINITE = -1',
    'public static android.animation.ValueAnimator ofInt(int... values)',
    'public static android.animation.ValueAnimator ofFloat(float... values)',
    'public void setDuration(long duration)',
    'public void setRepeatCount(int value)',
    'public void setInterpolator(android.animation.TimeInterpolator value)',
    'public Object getAnimatedValue()',
    'public void addUpdateListener(android.animation.ValueAnimator.AnimatorUpdateListener listener)',
    'public void addListener(android.animation.AnimatorListenerAdapter listener)',
    'public void start()',
    'public void cancel()',
    'public boolean isStarted()',
    'public interface AnimatorUpdateListener {'
    ' void onAnimationUpdate(android.animation.ValueAnimator animation); }',
], extends='android.animation.Animator')

# ---------------------------------------------------------------- android.webkit
c('android.webkit.MimeTypeMap', [
    'private static final android.webkit.MimeTypeMap SINGLETON = new android.webkit.MimeTypeMap()',
    'public static android.webkit.MimeTypeMap getSingleton() { return SINGLETON; }',
    'public String getMimeTypeFromExtension(String extension) { return null; }',
    'public String getExtensionFromMimeType(String mimeType) { return null; }',
])
c('android.webkit.ValueCallback', ['void onReceiveValue(T value);'], kind='interface',
  generics='<T>')
c('android.webkit.CookieManager', [
    'public static android.webkit.CookieManager getInstance()',
    'public String getCookie(String url)',
    'public void setAcceptCookie(boolean accept)',
    'public void setAcceptThirdPartyCookies(android.webkit.WebView webview, boolean accept)',
    'public void flush()',
])
c('android.webkit.WebResourceRequest', [
    'android.net.Uri getUrl();',
], kind='interface')
c('android.webkit.WebResourceResponse', [
    'public WebResourceResponse(String mimeType, String encoding, java.io.InputStream data)',
])
c('android.webkit.WebSettings', [
    'public void setJavaScriptEnabled(boolean flag)',
    'public void setDomStorageEnabled(boolean flag)',
    'public void setAllowFileAccess(boolean allow)',
    'public void setAllowContentAccess(boolean allow)',
    'public void setUserAgentString(String ua)',
    'public void setLoadsImagesAutomatically(boolean flag)',
    'public void setBlockNetworkImage(boolean flag)',
], kind='abstract')
c('android.webkit.WebViewClient', [
    'public boolean shouldOverrideUrlLoading(android.webkit.WebView view,'
    ' android.webkit.WebResourceRequest request)',
    'public boolean shouldOverrideUrlLoading(android.webkit.WebView view, String url)',
    'public android.webkit.WebResourceResponse shouldInterceptRequest(android.webkit.WebView view,'
    ' android.webkit.WebResourceRequest request)',
    'public android.webkit.WebResourceResponse shouldInterceptRequest(android.webkit.WebView view,'
    ' String url)',
    'public void onPageFinished(android.webkit.WebView view, String url)',
])
c('android.webkit.WebView', [
    'public WebView(android.content.Context context)',
    'public android.webkit.WebSettings getSettings()',
    'public void setWebViewClient(android.webkit.WebViewClient client)',
    'public void loadUrl(String url)',
    'public void stopLoading()',
    'public void destroy()',
    'public String getUrl()',
    'public void evaluateJavascript(String script, android.webkit.ValueCallback<String> resultCallback)',
], extends='android.widget.FrameLayout')

# ---------------------------------------------------------------- misc
c('android.annotation.SuppressLint', ['String[] value();'], kind='annotation')
c('android.R', [
    'public static final class attr { public static final int state_checked = 16842912; }',
    'public static final class anim { public static final int fade_in = 17432576;'
    ' public static final int fade_out = 17432577; }',
])


# Any return type, and `abstract` setters too: android.jar has builder-style
# setters (Intent.setType -> Intent) and abstract ones (Drawable.setColorFilter),
# and Kotlin property syntax needs a getter for all of them.
SETTER = re.compile(
    r'^public (?:abstract |final |static )*[\w.$\[\]<>]+ set([A-Z]\w*)\(([\w.$\[\]<> ]+?) \w+\)$')



# ---- APIs added for the current source tree --------------------------------
# The offline stub jar is a COMPILE-TIME contract; every symbol below is a real
# Android API at or below the app's minSdk-guarded usage. Keeping this in step
# with the source is what lets tools/build-offline.sh (and the behavioural
# CoreRegressionTests) run with no Android SDK installed.

c('android.util.Log', [
    'public static int e(String tag, String msg) { return 0; }',
    'public static int e(String tag, String msg, Throwable tr) { return 0; }',
    'public static int w(String tag, String msg) { return 0; }',
    'public static int d(String tag, String msg) { return 0; }',
])

c('android.os.Process', [
    'public static int myPid() { return 0; }',
    'public static void killProcess(int pid)',
])

c('android.app.Application', [
    'public void onCreate()',
], extends='android.content.Context')

c('android.app.AlarmManager', [
    'public static final int RTC = 1',
    'public static final int RTC_WAKEUP = 0',
    'public void set(int type, long triggerAtMillis, android.app.PendingIntent operation)',
])

c('android.animation.TimeInterpolator', ['float getInterpolation(float input)'], kind='interface')
c('android.animation.LayoutTransition', [
    'public static final int CHANGE_APPEARING = 0',
    'public static final int CHANGE_DISAPPEARING = 1',
    'public static final int APPEARING = 2',
    'public static final int DISAPPEARING = 3',
    'public static final int CHANGING = 4',
    'public void setDuration(int transitionType, long duration)',
    'public void setDuration(long duration)',
    'public void enableTransitionType(int transitionType)',
    'public void disableTransitionType(int transitionType)',
    'public void setInterpolator(int transitionType,'
    ' android.animation.TimeInterpolator interpolator)',
    'public void setStartDelay(int transitionType, long delay)',
])

c('android.view.animation.LinearInterpolator',
  ['public float getInterpolation(float input) { return input; }'],
  implements='android.view.animation.Interpolator')
c('android.view.animation.PathInterpolator', [
    'public PathInterpolator(float controlX1, float controlY1, float controlX2, float controlY2)',
    'public float getInterpolation(float input) { return input; }',
], implements='android.view.animation.Interpolator')

c('android.view.ViewConfiguration', [
    'public static android.view.ViewConfiguration get(android.content.Context context)',
    'public int getScaledTouchSlop()',
    'public int getScaledMinimumFlingVelocity()',
    'public static int getLongPressTimeout() { return 500; }',
])

c('android.view.VelocityTracker', [
    'public static android.view.VelocityTracker obtain()',
    'public void addMovement(android.view.MotionEvent event)',
    'public void computeCurrentVelocity(int units)',
    'public float getYVelocity()',
    'public void clear()',
    'public void recycle()',
])

c('android.text.Layout', [
    'public int getLineForVertical(int vertical)',
    'public float getLineLeft(int line)',
    'public float getLineRight(int line)',
    'public int getOffsetForHorizontal(int line, float horiz)',
], kind='abstract')

c('android.view.textclassifier.TextClassifier', [
    'public static final android.view.textclassifier.TextClassifier NO_OP = null',
], kind='interface')

c('android.text.style.ClickableSpan', [
    'public void onClick(android.view.View widget)',
], kind='abstract')


def _auto_getters(members):
    """android.jar pairs a getter with nearly every setter; Kotlin property
    assignment (`view.orientation = x`) needs one. Synthesise the missing ones."""
    have = set()
    for m in members:
        g = re.match(r'^public [\w.$\[\]<> ]+ (get|is)([A-Z]\w*)\(\)$', m.strip())
        if g:
            have.add(g.group(2))
    out = []
    for m in members:
        hit = SETTER.match(m.strip())
        if not hit:
            continue
        name, typ = hit.group(1), hit.group(2).strip()
        if name in have:
            continue
        have.add(name)
        out.append('public %s get%s()' % (typ, name))
        if typ == 'boolean':
            out.append('public boolean is%s()' % name)
    return out


# ---- JDK members Android only gained after the floor -----------------------
# java.* comes from the real JDK, so these have no stub to hang a tag on — but
# they fail on device exactly like a missing android.* method, because Android's
# core library is versioned too. `Math.floorMod` (API 24) is how KeyRouter
# crashed every Android 6 user who had a second API key.
#
# Names only, so anything with a common spelling is deliberately absent:
# `merge` would hit Think.merge, `join` would hit Thread.join, and `forEach` is
# a Kotlin stdlib extension on everything. See "WHAT IT DOES NOT CATCH".
EXTRA_SINCE = {
    'putIfAbsent': 24,       # Map.putIfAbsent
    'getOrDefault': 24,      # Map.getOrDefault
    'computeIfAbsent': 24,   # Map.computeIfAbsent
    'computeIfPresent': 24,  # Map.computeIfPresent
    'floorMod': 24,          # Math.floorMod
    'floorDiv': 24,          # Math.floorDiv
    'toIntExact': 24,        # Math.toIntExact
    'removeIf': 24,          # Collection.removeIf
}

# ---- Known defects in files this generator cannot fix -----------------------
# (source file, symbol) -> what the fix is.
#
# These are REAL NoSuchMethodError sites on Android 6. They are printed on every
# run and deliberately NOT fatal, so one unfixed file cannot block the offline
# build for everything else. The list is meant to be empty; an entry that no
# longer matches anything is reported too, so a waiver cannot outlive its bug.
PENDING_UNGUARDED = {
    ('MarkdownRenderer.kt', 'fromHtml'):
        'Html.fromHtml(String, int) is API 24 — needs an SDK_INT >= 24 branch '
        'with the deprecated one-arg Html.fromHtml(html) as the else.',
    ('Tools.kt', 'dataDir'):
        'Context.getDataDir() is API 24 — guard it, or use '
        'filesDir.parentFile on 23. (The extra hits on the neighbouring lines '
        'are the local `val dataDir` shadowing the tagged name — see '
        '"Shadowing" in the module docstring.)',
}


def harvest_levels():
    """Fills SINCE from every '@since<level>' tag in the class specs."""
    for spec in S.values():
        for member in spec[3]:
            tag = SINCE_TAG.match(member.strip())
            if not tag:
                continue
            decl = SINCE_TAG.sub('', member.strip(), count=1)
            for symbol in _symbols(decl):
                SINCE[symbol] = max(SINCE.get(symbol, 0), int(tag.group(1)))
    for symbol, level in EXTRA_SINCE.items():
        SINCE[symbol] = max(SINCE.get(symbol, 0), level)


# ---- Source scanning --------------------------------------------------------

def blank_noncode(source):
    r"""Blank comments, string/char literals and import lines, keeping newlines.

    Every offset in the result is its offset in the original, so
    `text[:offset].count('\n')` stays the true line number. Imports are blanked
    too: `import android.app.NotificationChannel` is not a call site, and
    leaving it in makes every guarded constructor look unguarded.
    """
    out = []
    i, n = 0, len(source)
    while i < n:
        ch = source[i]
        if source.startswith('//', i):
            end = source.find('\n', i)
            end = n if end < 0 else end
            out.append(' ' * (end - i))
            i = end
        elif source.startswith('/*', i):
            depth, j = 0, i
            while j < n:
                if source.startswith('/*', j):
                    depth += 1
                    j += 2
                elif source.startswith('*/', j):
                    depth -= 1
                    j += 2
                    if depth == 0:
                        break
                else:
                    j += 1
            out.append(''.join(c if c == '\n' else ' ' for c in source[i:j]))
            i = j
        elif source.startswith('"""', i):
            end = source.find('"""', i + 3)
            end = n if end < 0 else end + 3
            out.append(''.join(c if c == '\n' else ' ' for c in source[i:end]))
            i = end
        elif ch in '"\'':
            j = i + 1
            while j < n and source[j] != ch and source[j] != '\n':
                j += 2 if source[j] == '\\' else 1
            j = min(j + 1, n)
            out.append(''.join(c if c == '\n' else ' ' for c in source[i:j]))
            i = j
        else:
            out.append(ch)
            i += 1
    return re.sub(r'^(?:import|package)[^\n]*',
                  lambda m: ' ' * len(m.group(0)), ''.join(out), flags=re.MULTILINE)


GUARD = re.compile(r'Build\.VERSION\.SDK_INT\s*(>=|>|<=|<|==)\s*(\d+)')


def _match_brace(code, open_at):
    depth = 0
    for i in range(open_at, len(code)):
        if code[i] == '{':
            depth += 1
        elif code[i] == '}':
            depth -= 1
            if depth == 0:
                return i
    return len(code) - 1


def guard_regions(code):
    """[(start, end, level)] for every `Build.VERSION.SDK_INT` decision.

    A region spans the WHOLE if/else construct, both branches, at the level
    named in the condition. `if (SDK_INT >= 24) recent() else legacy()` is one
    considered decision and both halves belong to it — which is why this proves
    "an API-level decision surrounds the call", not "the call runs only above
    the level". For a `<` form whose body returns, the region runs to the end of
    the enclosing block instead: an early `if (SDK_INT < 26) return` guards
    everything after it.
    """
    regions = []
    for hit in GUARD.finditer(code):
        op, level = hit.group(1), int(hit.group(2))
        if op in ('>', '<='):
            level += 1
        start = code.rfind('\n', 0, hit.start()) + 1
        brace = code.find('{', hit.end())
        # A brace on the far side of a blank line belongs to something else; all
        # this guard can then be trusted to cover is its own line (which is
        # enough for `if (SDK_INT >= 30 && isExternalStorageManager())`).
        if brace < 0 or '\n\n' in code[hit.end():brace]:
            eol = code.find('\n', hit.end())
            regions.append((start, len(code) if eol < 0 else eol, level))
            continue
        body_end = _match_brace(code, brace)
        end = body_end
        # Follow `} else {` / `} else if (…) {` chains to the end of the chain.
        while re.match(r'\s*else\b', code[end + 1:end + 40]):
            nxt = code.find('{', end + 1)
            if nxt < 0 or '\n\n' in code[end + 1:nxt]:
                eol = code.find('\n', end + 1)
                end = len(code) - 1 if eol < 0 else eol
                break
            end = _match_brace(code, nxt)
        if op in ('<', '<=') and re.search(r'\breturn\b', code[brace:body_end + 1]):
            depth, i = 0, end + 1
            while i < len(code):
                if code[i] == '{':
                    depth += 1
                elif code[i] == '}':
                    if depth == 0:
                        break
                    depth -= 1
                i += 1
            end = min(i, len(code) - 1)
        regions.append((start, end, level))
    return regions


def check_api_floor():
    """Returns (fatal, pending, stale) findings over every .kt file in src/."""
    watched = {s: lvl for s, lvl in SINCE.items() if lvl > MIN_SDK}
    fatal, waived = [], {}
    if not watched or not os.path.isdir(SRC):
        return fatal, [], []
    pattern = re.compile(r'\b(' + '|'.join(sorted(re.escape(s) for s in watched)) + r')\b')
    for folder, _dirs, files in os.walk(SRC):
        for filename in sorted(files):
            if not filename.endswith('.kt'):
                continue
            with open(os.path.join(folder, filename), encoding='utf-8') as handle:
                code = blank_noncode(handle.read())
            regions = guard_regions(code)
            for hit in pattern.finditer(code):
                symbol = hit.group(1)
                need = watched[symbol]
                if any(lo <= hit.start() < hi and lvl >= need for lo, hi, lvl in regions):
                    continue
                line = code[:hit.start()].count('\n') + 1
                if (filename, symbol) in PENDING_UNGUARDED:
                    waived.setdefault((filename, symbol), []).append(line)
                else:
                    fatal.append('%s:%d  %s  needs API %d, minSdk is %d'
                                 % (filename, line, symbol, need, MIN_SDK))
    # One line per waived defect, not one per hit: a shadowing local name can
    # multiply a single bug into four identical paragraphs.
    pending = ['%s  %s  lines %s  (API %d)\n        %s'
               % (name, symbol, ','.join(str(n) for n in sorted(set(lines))),
                  watched[symbol], PENDING_UNGUARDED[(name, symbol)])
               for (name, symbol), lines in sorted(waived.items())]
    stale = ['%s / %s' % key for key in sorted(PENDING_UNGUARDED) if key not in waived]
    return fatal, pending, stale


def verify_min_sdk():
    """MIN_SDK must equal the floor every other part of the build writes down."""
    wrong = []
    for name, pattern in (('build.gradle.kts', r'minSdk\s*=\s*(\d+)'),
                          ('AndroidManifest.xml', r'android:minSdkVersion="(\d+)"'),
                          ('mkapk.sh', r'--min-sdk-version (\d+)'),
                          ('mkapk.sh', r'--min-api (\d+)')):
        path = os.path.join(ROOT, name)
        if not os.path.isfile(path):
            continue
        with open(path, encoding='utf-8') as handle:
            for value in re.findall(pattern, handle.read()):
                if int(value) != MIN_SDK:
                    wrong.append('%s says %s, MIN_SDK says %d (%s)'
                                 % (name, value, MIN_SDK, pattern))
    return wrong


def render(fq, spec):
    kind, extends, implements, members, generics = spec
    pkg, name = fq.rsplit('.', 1)
    lines = ['package %s;' % pkg, '',
             '/** Compile-time stub — never executed on device. */']
    head = 'public '
    if kind == 'abstract':
        head += 'abstract class '
    elif kind == 'interface':
        head += 'interface '
    elif kind == 'annotation':
        head += '@interface '
    else:
        head += 'class '
    head += name + generics
    if extends:
        head += ' extends ' + extends
    if implements:
        head += (' extends ' if kind == 'interface' else ' implements ') + implements
    # Strip the @since tags into a per-member note. The declaration itself must
    # be plain Java by the time _auto_getters and the emitter see it.
    levels = {}
    plain = []
    for member in members:
        tag = SINCE_TAG.match(member.strip())
        if tag:
            decl = SINCE_TAG.sub('', member.strip(), count=1)
            levels[decl] = int(tag.group(1))
            plain.append(decl)
        else:
            plain.append(member)
    members = plain + _auto_getters(plain)
    lines.append(head + ' {')
    # A stub subclass's implicit super() needs a no-arg ctor on the parent, so
    # every class that declares constructors also gets a protected no-arg one
    # (unless it already declares one).
    if kind in ('class', 'abstract'):
        decls = [m for m in members if m.strip().startswith('public ' + name + '(')]
        if decls and not any(m.strip().startswith('public %s()' % name) for m in members):
            lines.append('    protected %s() { }' % name)
        elif not decls:
            lines.append('    public %s() { }' % name)
    for m in members:
        m = m.strip()
        if m in levels:
            # The generated tree records the level too, so a reader of the stub
            # can see it without coming back here.
            lines.append('    /** @since API %d */' % levels[m])
        if m.endswith('}') or m.endswith(';'):
            lines.append('    ' + m)                      # nested type / iface method
        elif '=' in m.split('(')[0]:
            lines.append('    ' + m + ';')                # field with initialiser
        elif m.startswith(('public int ', 'public float ', 'public long ',
                           'public boolean ', 'public double ')) and '(' not in m:
            lines.append('    ' + m + ';')                # bare field
        elif kind == 'interface':
            lines.append('    ' + m.replace('public ', '') + ';')
        elif m.startswith('public abstract ') or kind == 'annotation':
            lines.append('    ' + m + ';')
        else:
            lines.append('    ' + m + ' { throw new RuntimeException("Stub!"); }')
    lines.append('}')
    return '\n'.join(lines) + '\n'


def main():
    if os.path.isdir(OUT):
        shutil.rmtree(OUT)
    count = 0
    for fq, spec in S.items():
        pkg, name = fq.rsplit('.', 1)
        d = os.path.join(OUT, *pkg.split('.'))
        os.makedirs(d, exist_ok=True)
        with open(os.path.join(d, name + '.java'), 'w', encoding='utf-8') as fh:
            fh.write(render(fq, spec))
        count += 1
    print('generated %d stub classes into %s' % (count, OUT))

    if '--no-api-check' in _FLAGS:
        return
    harvest_levels()
    tagged = sum(1 for level in SINCE.values() if level > MIN_SDK)

    drift = verify_min_sdk()
    fatal, pending, stale = check_api_floor()

    print('api floor: minSdk %d, %d member(s) tagged above it' % (MIN_SDK, tagged))
    for note in pending:
        print('  PENDING  ' + note)
    for note in stale:
        print('  STALE WAIVER (the site is gone; delete the entry)  ' + note)
    for note in drift:
        print('  MIN_SDK DRIFT  ' + note)
    for note in fatal:
        print('  UNGUARDED  ' + note)

    if not (drift or fatal):
        print('api floor: OK')
        return
    if '--warn-only' in _FLAGS:
        print('api floor: FAILED (downgraded by --warn-only)')
        return
    print('')
    print('An API-level-tagged member is used above minSdk with no')
    print('Build.VERSION.SDK_INT decision around it. That compiles clean against')
    print('every android.jar and throws NoSuchMethodError on the device — it is')
    print('the exact shape of the stopForeground(STOP_FOREGROUND_REMOVE) bug.')
    print('Wrap the call in an SDK_INT branch with a pre-floor equivalent.')
    sys.exit(1)


if __name__ == '__main__':
    main()
