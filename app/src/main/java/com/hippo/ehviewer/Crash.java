/*
 * Copyright 2019 Hippo Seven
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hippo.ehviewer;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Debug;
import androidx.annotation.NonNull;
import com.hippo.scene.StageActivity;
import com.hippo.util.PackageUtils;
import com.hippo.util.ReadableTime;
import com.hippo.lib.yorozuya.FileUtils;
import com.hippo.lib.yorozuya.IOUtils;
import com.hippo.lib.yorozuya.OSUtils;
import java.io.File;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;
import java.io.FileWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;

public final class Crash {
  private Crash() {}

  @NonNull
  private static String avoidNull(String str) {
    return null != str ? str : "null";
  }

  @SuppressLint("HardwareIds")
  private static void collectInfo(Context context, Writer fw) throws IOException {
    try {
      PackageManager pm = context.getPackageManager();
      PackageInfo pi = pm.getPackageInfo(context.getPackageName(), PackageManager.GET_ACTIVITIES);
      if (pi != null) {
        String versionName = pi.versionName == null ? "null" : pi.versionName;
        String versionCode = String.valueOf(pi.versionCode);
        fw.write("======== PackageInfo ========\r\n");
        fw.write("PackageName=");fw.write(pi.packageName);fw.write("\r\n");
        fw.write("VersionName=");fw.write(versionName);fw.write("\r\n");
        fw.write("VersionCode=");fw.write(versionCode);fw.write("\r\n");
        String signature = PackageUtils.getSignature(context, pi.packageName);
        fw.write("Signature=");fw.write(null != signature ? signature : "null");fw.write("\r\n");
        fw.write("\r\n");
      }
    } catch (PackageManager.NameNotFoundException e) {
      fw.write("======== PackageInfo ========\r\n");
      fw.write("Can't get package information\r\n");
      fw.write("\r\n");
    }

    // Runtime
    String topActivityClazzName = "null";
    String topSceneClazzName = "null";
    try {
      Activity topActivity = ((EhApplication) context.getApplicationContext()).getTopActivity();
      if (null != topActivity) {
        topActivityClazzName = topActivity.getClass().getName();
        if (topActivity instanceof StageActivity) {
          Class<?> clazz = ((StageActivity) topActivity).getTopSceneClass();
          if (clazz != null) {
            topSceneClazzName = clazz.getName();
          }
        }
      }
    } catch (Throwable e) {
      // Ignore
    }
    fw.write("======== Runtime ========\r\n");
    fw.write("TopActivity=");fw.write(avoidNull(topActivityClazzName));fw.write("\r\n");
    fw.write("TopScene=");fw.write(avoidNull(topSceneClazzName));fw.write("\r\n");
    fw.write("\r\n");

    // Device info
    fw.write("======== DeviceInfo ========\r\n");
    fw.write("BOARD=");fw.write(Build.BOARD);fw.write("\r\n");
    fw.write("BOOTLOADER=");fw.write(Build.BOOTLOADER);fw.write("\r\n");
    fw.write("CPU_ABI=");fw.write(Build.CPU_ABI);fw.write("\r\n");
    fw.write("CPU_ABI2=");fw.write(Build.CPU_ABI2);fw.write("\r\n");
    fw.write("DEVICE=");fw.write(Build.DEVICE);fw.write("\r\n");
    fw.write("DISPLAY=");fw.write(Build.DISPLAY);fw.write("\r\n");
    fw.write("FINGERPRINT=");fw.write(Build.FINGERPRINT);fw.write("\r\n");
    fw.write("HARDWARE=");fw.write(Build.HARDWARE);fw.write("\r\n");
    fw.write("HOST=");fw.write(Build.HOST);fw.write("\r\n");
    fw.write("ID=");fw.write(Build.ID);fw.write("\r\n");
    fw.write("MANUFACTURER=");fw.write(Build.MANUFACTURER);fw.write("\r\n");
    fw.write("MODEL=");fw.write(Build.MODEL);fw.write("\r\n");
    fw.write("PRODUCT=");fw.write(Build.PRODUCT);fw.write("\r\n");
    fw.write("RADIO=");fw.write(Build.getRadioVersion());fw.write("\r\n");
    fw.write("SERIAL=");fw.write(Build.SERIAL);fw.write("\r\n");
    fw.write("TAGS=");fw.write(Build.TAGS);fw.write("\r\n");
    fw.write("TYPE=");fw.write(Build.TYPE);fw.write("\r\n");
    fw.write("USER=");fw.write(Build.USER);fw.write("\r\n");
    fw.write("CODENAME=");fw.write(Build.VERSION.CODENAME);fw.write("\r\n");
    fw.write("INCREMENTAL=");fw.write(Build.VERSION.INCREMENTAL);fw.write("\r\n");
    fw.write("RELEASE=");fw.write(Build.VERSION.RELEASE);fw.write("\r\n");
    fw.write("SDK=");fw.write(Integer.toString(Build.VERSION.SDK_INT));fw.write("\r\n");
    fw.write("MEMORY=");fw.write(
        FileUtils.humanReadableByteCount(OSUtils.getAppAllocatedMemory(), false));fw.write("\r\n");
    fw.write("MEMORY_NATIVE=");fw.write(FileUtils.humanReadableByteCount(Debug.getNativeHeapAllocatedSize(), false));fw.write("\r\n");
    fw.write("MEMORY_MAX=");fw.write(FileUtils.humanReadableByteCount(OSUtils.getAppMaxMemory(), false));fw.write("\r\n");
    fw.write("MEMORY_TOTAL=");fw.write(FileUtils.humanReadableByteCount(OSUtils.getTotalMemory(), false));fw.write("\r\n");
    fw.write("\r\n");
  }

  private static void getThrowableInfo(Throwable t, Writer fw) {
    PrintWriter printWriter = new PrintWriter(fw);
    t.printStackTrace(printWriter);
    Throwable cause = t.getCause();
    while (cause != null) {
      cause.printStackTrace(printWriter);
      cause = cause.getCause();
    }
  }

  /**
   * 依次尝试几个目录写崩溃日志。
   *
   * <p>{@code /sdcard/EhViewer/crash} 在 Android 11+ 上可能因为分区存储写不进去，
   * 因此再退到应用专属目录 {@code /sdcard/Android/data/<包名>/files/crash}（无需任何权限），
   * 最后退到内部存储。三个都写不了才放弃。
   */
  private static List<File> candidateDirs(Context context) {
    List<File> dirs = new ArrayList<>(3);
    File external = AppConfig.getExternalCrashDir();
    if (null != external) {
      dirs.add(external);
    }
    File appExternal = context.getExternalFilesDir("crash");
    if (null != appExternal) {
      dirs.add(appExternal);
    }
    dirs.add(new File(context.getFilesDir(), "crash"));
    return dirs;
  }

  private static boolean ensureDir(File dir) {
    return dir.isDirectory() || dir.mkdirs();
  }

  /**
   * 额外往“下载”目录写一份（API 29+ 通过 MediaStore 写入，不需要存储权限），
   * 这样用任意文件管理器都能找到，不用去翻 Android/data。
   */
  private static void saveToDownloads(Context context, String fileName, String content) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
      return;
    }
    try {
      ContentValues values = new ContentValues();
      values.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
      values.put(MediaStore.Downloads.MIME_TYPE, "text/plain");
      values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/EhViewer");
      values.put(MediaStore.Downloads.IS_PENDING, 1);
      ContentResolver resolver = context.getContentResolver();
      Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
      if (uri == null) {
        return;
      }
      OutputStream os = resolver.openOutputStream(uri);
      if (os == null) {
        return;
      }
      os.write(content.getBytes("UTF-8"));
      os.flush();
      os.close();
      values.clear();
      values.put(MediaStore.Downloads.IS_PENDING, 0);
      resolver.update(uri, values, null, null);
      android.util.Log.e("Crash", "崩溃日志已保存到下载目录: " + fileName);
    } catch (Throwable ignored) {
      // 写不进去就算了，还有其他目录兜底
    }
  }

  /**
   * 把崩溃栈直接放进剪贴板。
   *
   * <p>闪退后用户未必会去翻文件管理器，但“长按粘贴”几乎人人都会。
   * 剪贴板写入不需要任何权限，这里作为最容易拿到日志的通道。
   */
  private static void copyToClipboard(Context context, String content) {
    try {
      Object service = context.getSystemService(Context.CLIPBOARD_SERVICE);
      if (!(service instanceof android.content.ClipboardManager)) {
        return;
      }
      String text = content.length() > 8000 ? content.substring(0, 8000) : content;
      ((android.content.ClipboardManager) service)
          .setPrimaryClip(android.content.ClipData.newPlainText("crash", text));
    } catch (Throwable ignored) {
      // 剪贴板失败不影响其他落盘路径
    }
  }

  /**
   * 用固定文件名在内部存储留一份，供下次启动时读出并提醒用户。
   * 内部存储一定能写，不需要任何权限。
   */
  private static void saveLastCrash(Context context, String content) {
    try {
      File dir = new File(context.getFilesDir(), "crash");
      if (!dir.isDirectory() && !dir.mkdirs()) {
        return;
      }
      File file = new File(dir, "crash-last.log");
      FileWriter fw = new FileWriter(file);
      fw.write(content);
      fw.flush();
      fw.close();
    } catch (Throwable ignored) {
    }
  }

  public static void saveCrashLog(Context context, Throwable t) {
    String nowString = ReadableTime.getFilenamableTime(System.currentTimeMillis());
    String fileName = "crash-" + nowString + ".log";

    // 先拼出完整内容，下载目录那份也要用
    StringWriter sw = new StringWriter();
    String full;
    try {
      sw.write("TIME=");
      sw.write(nowString);
      sw.write("\r\n\r\n");
      collectInfo(context, sw);
      sw.write("======== CrashInfo ========\r\n");
      getThrowableInfo(t, sw);
      sw.write("\r\n");
      full = sw.toString();
    } catch (Exception e) {
      return;
    }
    saveToDownloads(context, fileName, full);
    saveLastCrash(context, full);
    copyToClipboard(context, full);

    for (File dir : candidateDirs(context)) {
      if (!ensureDir(dir)) {
        continue;
      }
      File file = new File(dir, fileName);
      FileWriter fw = null;
      try {
        fw = new FileWriter(file);
        fw.write("TIME=");fw.write(nowString);fw.write("\r\n");
        fw.write("\r\n");
        collectInfo(context, fw);
        fw.write("======== CrashInfo ========\r\n");
        getThrowableInfo(t, fw);
        fw.write("\r\n");
        fw.flush();
        IOUtils.closeQuietly(fw);
        fw = null;
        android.util.Log.e("Crash", "崩溃日志已保存: " + file.getAbsolutePath());
        return; // 写成功就停，不重复写多份
      } catch (Exception e) {
        file.delete();
      } finally {
        IOUtils.closeQuietly(fw);
      }
    }
  }
}
