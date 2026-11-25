import java.net.URI
import java.security.MessageDigest

// Pinned build inputs. OCR data ships in the APK; the 190 MB Whisper model is downloaded by the app on first use
// (see SpeechModel.kt, same URL and SHA-256), so it is no longer bundled.
val downloads = layout.buildDirectory.dir("media-downloads")
val assets = layout.buildDirectory.dir("generated/mediaAssets")
val nativeSource = layout.buildDirectory.dir("whisper-source")
val files = listOf(
    Triple("rus.traineddata", "https://raw.githubusercontent.com/tesseract-ocr/tessdata_fast/4.1.0/rus.traineddata", "e16e5e036cce1d9ec2b00063cf8b54472625b9e14d893a169e2b0dedeb4df225"),
    Triple("eng.traineddata", "https://raw.githubusercontent.com/tesseract-ocr/tessdata_fast/4.1.0/eng.traineddata", "7d4322bd2a7749724879683fc3912cb542f19906c83bcc1a52132556427170b2"),
    Triple("whisper-1.7.6.tar.gz", "https://codeload.github.com/ggml-org/whisper.cpp/tar.gz/refs/tags/v1.7.6", "166140e9a6d8a36f787a2bd77f8f44dd64874f12dd8359ff7c1f4f9acb86202e")
)
val prepareMedia = tasks.register("prepareOfflineMedia") {
    inputs.property("sources", files.toString())
    outputs.dir(assets)
    outputs.dir(nativeSource)
    doLast {
        fun digest(file: File): String {
            val sha = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input -> java.security.DigestInputStream(input, sha).copyTo(java.io.OutputStream.nullOutputStream()) }
            return sha.digest().joinToString("") { "%02x".format(it) }
        }
        for ((name, url, hash) in files) {
            val file = downloads.get().file(name).asFile
            file.parentFile.mkdirs()
            if (!file.exists() || digest(file) != hash) {
                val part = File(file.parentFile, "$name.part")
                URI(url).toURL().openConnection().apply { connectTimeout = 30000; readTimeout = 120000 }
                    .getInputStream().use { src -> part.outputStream().use { src.copyTo(it) } }
                check(digest(part) == hash) { "Wrong SHA-256 for $name" }
                java.nio.file.Files.move(part.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            }
            if (name.endsWith(".gz")) copy {
                from(tarTree(resources.gzip(file)))
                into(nativeSource)
                include("*/CMakeLists.txt", "*/cmake/**", "*/ggml/**", "*/include/**", "*/src/**", "*/LICENSE", "*/samples/jfk.wav")
            }
        }
        sync {
            from(downloads) { include(files.map { it.first }.filterNot { it.endsWith(".gz") }) }
            into(assets)
        }
    }
}
tasks.matching { it.name == "preBuild" || it.name.startsWith("configureCMake") || it.name.endsWith("Assets") && it.name.startsWith("merge") }
    .configureEach { dependsOn(prepareMedia) }
