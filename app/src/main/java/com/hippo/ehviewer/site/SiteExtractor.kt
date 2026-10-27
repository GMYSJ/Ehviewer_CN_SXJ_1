package com.hippo.ehviewer.site

import net.lingala.zip4j.ZipFile
import java.io.File

/**
 * 压缩包解压。
 *
 * 作者放的压缩包绝大部分带密码，而密码写法五花八门，所以这里把
 * [PasswordExtractor] 给出的候选逐个尝试；都失败了返回 null，由上层让用户手输。
 */
object SiteExtractor {

    /**
     * 尝试解压到 [dest]。
     *
     * @return 成功返回 dest，全部密码都不对（或文件损坏）返回 null
     */
    fun extract(zip: File, dest: File, passwords: List<String>): File? {
        if (!zip.isFile) return null
        if (!dest.exists() && !dest.mkdirs()) return null

        // 先试无密码，再试提取出来的候选
        val candidates: List<String> = listOf("") + passwords
        for (password in candidates) {
            try {
                val zipFile = ZipFile(zip)
                if (zipFile.isEncrypted) {
                    if (password.isEmpty()) continue
                    zipFile.setPassword(password.toCharArray())
                }
                zipFile.extractAll(dest.absolutePath)
                if (dest.listFiles()?.isNotEmpty() == true) return dest
            } catch (e: Exception) {
                // 密码错误或包损坏，继续下一个候选
                clearBroken(dest)
            }
        }
        return null
    }

    /**
     * 解压半途失败会留下碎片，重下一个密码前先清干净。
     */
    private fun clearBroken(dest: File) {
        try {
            dest.listFiles()?.forEach { it.deleteRecursively() }
        } catch (e: Exception) {
            // 忽略，反正接下来还会再写
        }
    }
}
