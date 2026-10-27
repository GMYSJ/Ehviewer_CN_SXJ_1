package com.hippo.ehviewer.site

import android.content.Context
import android.util.Log
import android.util.SparseArray
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.EhApplication
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.client.EhUtils
import com.hippo.ehviewer.dao.DownloadInfo
import com.hippo.ehviewer.gallery.GalleryProvider2
import com.hippo.ehviewer.spider.SpiderDen
import com.hippo.ehviewer.spider.SpiderInfo
import com.hippo.ehviewer.spider.SpiderQueen
import com.hippo.lib.yorozuya.StringUtils
import com.hippo.lib.yorozuya.Utilities
import com.hippo.unifile.UniFile
import com.hippo.util.FileUtils
import com.hippo.util.NaturalComparator
import java.io.File
import java.util.Collections

/**
 * 把已经落在磁盘上的图片目录登记成已完成画廊，
 * 之后就可以像正常下载一样在看图器里直接翻。
 *
 * 套路与 DownloadAlbumImporter 一致：伪造 gid/token，写 SpiderInfo，走 DownloadManager。
 */
object SiteImportHelper {

    private const val TAG = "SiteImportHelper"

    const val URI_PREFIX = "site-download:"

    private const val LOCAL_TOKEN = "site"
    private const val LOCAL_THUMB = "local"
    private const val LOCAL_UPLOADER = "Site Download"

    /**
     * @return 成功返回 gid，失败返回 -1
     */
    fun import(context: Context, title: String, imageDir: File, sourceUri: String): Long {
        val appContext = context.applicationContext
        var gid = -1L
        var downloadDir: UniFile? = null
        return try {
            val root = UniFile.fromFile(imageDir)
            if (root == null || !root.isDirectory) {
                Log.w(TAG, "导入目录不可用: $imageDir")
                return -1L
            }

            if (Settings.getDownloadLocation() == null) {
                Log.w(TAG, "下载位置未设置，无法导入")
                return -1L
            }

            val manager = EhApplication.getDownloadManager(appContext)
            if (manager != null && isAlreadyImported(manager, sourceUri)) {
                Log.d(TAG, "该内容已经导入过: $sourceUri")
                return -1L
            }

            val importRoot = resolveImportRoot(root)
            val images = collectImageFiles(importRoot ?: root)
            if (images.isEmpty()) {
                Log.w(TAG, "没有可识别的图片")
                return -1L
            }

            Collections.sort(images) { left, right ->
                NaturalComparator().compare(left.name, right.name)
            }

            gid = System.currentTimeMillis()
            val downloadInfo = createDownloadInfo(gid, title, sourceUri, images.size)
            val spiderDen = SpiderDen(downloadInfo)
            spiderDen.setMode(SpiderQueen.MODE_DOWNLOAD)
            if (!spiderDen.prepareDownloadStorage()) {
                cleanup(gid, null)
                return -1L
            }

            downloadDir = spiderDen.getDownloadDir()
            if (downloadDir == null) {
                cleanup(gid, null)
                return -1L
            }

            val copied = copyImages(images, downloadDir)
            if (copied == 0) {
                cleanup(gid, downloadDir)
                return -1L
            }

            downloadInfo.total = copied
            downloadInfo.finished = copied
            writeSpiderInfo(appContext, spiderDen, gid, copied)
            copyThumb(images[0], downloadDir)

            val managerNotNull = manager ?: run {
                cleanup(gid, downloadDir)
                return -1L
            }
            managerNotNull.addDownload(listOf(downloadInfo))
            gid
        } catch (e: Exception) {
            Log.e(TAG, "导入失败", e)
            cleanup(gid, downloadDir)
            -1L
        }
    }

    private fun createDownloadInfo(
        gid: Long,
        title: String,
        sourceUri: String,
        imageCount: Int,
    ): DownloadInfo {
        val downloadInfo = DownloadInfo()
        downloadInfo.gid = gid
        downloadInfo.token = LOCAL_TOKEN
        downloadInfo.title = title
        downloadInfo.titleJpn = null
        downloadInfo.thumb = LOCAL_THUMB
        downloadInfo.category = EhUtils.UNKNOWN
        downloadInfo.posted = null
        downloadInfo.uploader = LOCAL_UPLOADER
        downloadInfo.rating = -1.0f
        downloadInfo.state = DownloadInfo.STATE_FINISH
        downloadInfo.legacy = 0
        downloadInfo.time = gid
        downloadInfo.label = null
        downloadInfo.total = imageCount
        downloadInfo.finished = imageCount
        downloadInfo.archiveUri = sourceUri
        return downloadInfo
    }

    private fun copyImages(images: List<UniFile>, downloadDir: UniFile): Int {
        var count = 0
        for (picture in images) {
            if (!picture.isFile) continue
            val extension = getImageExtension(picture.name)
            val newName = SpiderDen.generateImageFilename(count, extension)
            var destFile = downloadDir.findFile(newName)
            if (destFile != null && destFile.exists() && !destFile.delete()) {
                continue
            }
            destFile = downloadDir.createFile(newName)
            if (destFile == null) {
                Log.e(TAG, "创建文件失败: $newName")
                continue
            }
            if (!FileUtils.copyFile(picture, destFile, false)) {
                Log.e(TAG, "复制失败: " + picture.name)
                destFile.delete()
                continue
            }
            count++
        }
        return count
    }

    private fun writeSpiderInfo(context: Context, spiderDen: SpiderDen, gid: Long, pages: Int) {
        val spiderInfo = SpiderInfo()
        spiderInfo.gid = gid
        spiderInfo.token = LOCAL_TOKEN
        spiderInfo.pages = pages
        spiderInfo.previewPages = 0
        spiderInfo.previewPerPage = 0
        spiderInfo.startPage = 0
        spiderInfo.pTokenMap = SparseArray<String>(pages)
        spiderInfo.writeNewSpiderInfoToLocal(spiderDen, context)
    }

    private fun copyThumb(firstImage: UniFile, downloadDir: UniFile) {
        var thumbFile = downloadDir.findFile(".thumb")
        if (thumbFile != null && thumbFile.exists()) {
            thumbFile.delete()
        }
        thumbFile = downloadDir.createFile(".thumb")
        if (thumbFile == null) return
        if (!FileUtils.copyFile(firstImage, thumbFile, false)) {
            thumbFile.delete()
        }
    }

    /**
     * 目录里全是子文件夹时（比如解压出一层壳），往下钻，直到某一层直接有图片。
     */
    private fun resolveImportRoot(dir: UniFile): UniFile? {
        if (!dir.isDirectory) return null
        val children = dir.listFiles() ?: return null
        var hasImageAtLevel = false
        var onlySubdir: UniFile? = null
        var subdirCount = 0
        for (child in children) {
            if (child.isFile && isImageFile(child)) {
                hasImageAtLevel = true
            } else if (child.isDirectory) {
                onlySubdir = child
                subdirCount++
            }
        }
        if (hasImageAtLevel) return dir
        if (subdirCount == 1 && onlySubdir != null) return resolveImportRoot(onlySubdir)
        return dir
    }

    private fun collectImageFiles(root: UniFile): MutableList<UniFile> {
        val images = ArrayList<UniFile>()
        collectRecursive(root, images)
        return images
    }

    private fun collectRecursive(dir: UniFile, images: MutableList<UniFile>) {
        val children = dir.listFiles() ?: return
        for (child in children) {
            if (child.isDirectory) {
                collectRecursive(child, images)
            } else if (isImageFile(child)) {
                images.add(child)
            }
        }
    }

    private fun isImageFile(file: UniFile?): Boolean {
        if (file == null || !file.isFile) return false
        val name = file.name ?: return false
        return StringUtils.endsWith(name.lowercase(), GalleryProvider2.SUPPORT_IMAGE_EXTENSIONS)
    }

    private fun getImageExtension(fileName: String?): String {
        if (fileName == null) return GalleryProvider2.SUPPORT_IMAGE_EXTENSIONS[0]
        val dot = fileName.lastIndexOf('.')
        val extension = if (dot >= 0) fileName.substring(dot).lowercase() else ""
        return if (Utilities.contain(GalleryProvider2.SUPPORT_IMAGE_EXTENSIONS, extension)) {
            extension
        } else {
            GalleryProvider2.SUPPORT_IMAGE_EXTENSIONS[0]
        }
    }

    private fun isAlreadyImported(manager: com.hippo.ehviewer.download.DownloadManager, uri: String): Boolean {
        val all = manager.allDownloadInfoList ?: return false
        return all.any { uri == it?.archiveUri }
    }

    private fun cleanup(gid: Long, downloadDir: UniFile?) {
        try {
            downloadDir?.delete()
        } catch (e: Exception) {
            Log.w(TAG, "清理失败", e)
        }
        if (gid > 0) {
            EhDB.removeDownloadDirname(gid)
        }
    }
}
