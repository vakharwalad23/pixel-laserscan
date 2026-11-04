package dev.vakharwalad23.lasercore

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import java.io.File

class LaserFileProvider : FileProvider()

class LibraryModule : Module() {
  private val ctx: Context
    get() = appContext.reactContext ?: throw IllegalStateException("no context")

  private fun scansDir() = File(ctx.filesDir, "scans").apply { mkdirs() }

  override fun definition() = ModuleDefinition {
    Name("LaserLibrary")

    AsyncFunction("list") {
      scansDir().listFiles()?.filter { it.isDirectory }?.sortedByDescending { it.lastModified() }?.map { d ->
        val files = d.listFiles()?.filter { it.isFile }?.sortedBy { it.name } ?: emptyList()
        mapOf(
          "id" to d.name,
          "path" to d.absolutePath,
          "modifiedAt" to d.lastModified().toDouble(),
          "files" to files.map { mapOf("name" to it.name, "path" to it.absolutePath, "bytes" to it.length().toDouble()) },
          "meta" to (File(d, "meta.json").takeIf { it.exists() }?.readText() ?: "{}")
        )
      } ?: emptyList<Map<String, Any?>>()
    }

    AsyncFunction("remove") { id: String ->
      // Compare canonical paths so ".." and symlinks can't escape the scans directory.
      val root = scansDir().canonicalFile
      val d = File(root, id).canonicalFile
      if (id.isNotEmpty() && d.parentFile == root && d.isDirectory) d.deleteRecursively() else false
    }

    AsyncFunction("share") { path: String ->
      val f = File(path).canonicalFile
      if (!f.path.startsWith(scansDir().canonicalPath + File.separator) || !f.isFile) throw IllegalArgumentException("not a scan file")
      val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".lasercore.files", f)
      val send = Intent(Intent.ACTION_SEND).apply {
        type = when (f.extension.lowercase()) {
          "obj" -> "model/obj"
          "json" -> "application/json"
          else -> "application/octet-stream"
        }
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
      }
      val chooser = Intent.createChooser(send, "Share ${f.name}").apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
      }
      (appContext.currentActivity ?: ctx).startActivity(chooser)
      true
    }

    View(ModelView::class) {
      Events("onModelLoad")
      Prop("source") { view: ModelView, path: String -> view.setSource(path) }
      Prop("pointSize") { view: ModelView, v: Double -> view.setPointSize(v.toFloat()) }
      OnViewDestroys { view: ModelView -> view.release() }
    }
  }
}
