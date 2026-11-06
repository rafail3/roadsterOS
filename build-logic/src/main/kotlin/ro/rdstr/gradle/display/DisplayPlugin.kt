package ro.rdstr.gradle.display

import com.android.build.api.variant.AndroidComponentsExtension
import org.gradle.api.DefaultTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File

/**
 * `rdstr.display`: builds the display app's design from the browser emulator. The sheets the emulator loads (in the
 * order `emulator/app/layout.tsx` imports them) give the token classes, its icons the VectorDrawables. Both are
 * generated sources of the Android module that applies the plugin, rebuilt only when the CSS or the icons change.
 */
class DisplayPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val emulator = project.rootProject.layout.projectDirectory.dir("emulator")
        val task = project.tasks.register("generateDisplayUi", GenerateDisplayUi::class.java) {
            group = "rdstr"
            description = "Generates the design tokens and icons of the display app from the browser emulator."
            layout.set(emulator.file("app/layout.tsx"))
            styles.from(emulator.dir("styles").asFileTree.matching { include("*.css") })
            icons.from(emulator.dir("public/icons").asFileTree.matching { include("*.svg") })
            fonts.from(emulator.dir("public/fonts").asFileTree.matching { include("*.ttf", "*.otf", "*.woff", "*.woff2") })
            tool.set(listOf("chrome.css"))
            tokensPackage.set("ro.rdstr.ui.tokens")
            rPackage.set("ro.rdstr.ui")
            kotlinOut.set(project.layout.buildDirectory.dir("generated/rdstr/kotlin"))
            resOut.set(project.layout.buildDirectory.dir("generated/rdstr/res"))
        }
        project.extensions.getByType(AndroidComponentsExtension::class.java).onVariants { v ->
            v.sources.kotlin?.addGeneratedSourceDirectory(task, GenerateDisplayUi::kotlinOut)
            v.sources.res?.addGeneratedSourceDirectory(task, GenerateDisplayUi::resOut)
        }
    }
}

@CacheableTask
abstract class GenerateDisplayUi : DefaultTask() {
    @get:InputFile @get:PathSensitive(PathSensitivity.RELATIVE) abstract val layout: RegularFileProperty
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE) abstract val styles: ConfigurableFileCollection
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE) abstract val icons: ConfigurableFileCollection
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE) abstract val fonts: ConfigurableFileCollection
    /** Sheets of the emulator tool itself, not of the screen. */
    @get:Input abstract val tool: ListProperty<String>
    @get:Input abstract val tokensPackage: Property<String>
    @get:Input abstract val rPackage: Property<String>
    @get:OutputDirectory abstract val kotlinOut: DirectoryProperty
    @get:OutputDirectory abstract val resOut: DirectoryProperty

    @TaskAction
    fun generate() {
        val sheets = styles.files.associateBy { it.name }
        val order = DisplayBuild.sheetOrder(layout.get().asFile.readText(), tool.get())
        val missing = order.filter { it !in sheets }
        if (missing.isNotEmpty()) throw DisplayBuildException("layout.tsx imports ${missing.joinToString()} which is not in emulator/styles")
        val unused = sheets.keys - order.toSet() - tool.get().toSet()
        if (unused.isNotEmpty()) throw DisplayBuildException("emulator/styles has ${unused.joinToString()} which layout.tsx does not import")
        val result = DisplayBuild.run(order.map { it to sheets.getValue(it).readText() }, icons.files.associate { it.nameWithoutExtension to it.readText() },
            tokensPackage.get(), rPackage.get(), fonts.files.associate { it.name to it.readBytes() })
        val kotlin = kotlinOut.get().asFile.apply { deleteRecursively(); mkdirs() }
        val res = resOut.get().asFile.apply { deleteRecursively(); mkdirs() }
        val dir = File(kotlin, tokensPackage.get().replace('.', '/')).apply { mkdirs() }
        File(dir, "Tokens.kt").writeText(result.tokens)
        File(dir, "Icons.kt").writeText(result.icons)
        File(dir, "Fonts.kt").writeText(result.fonts)
        val fontDir = File(res, "font").apply { mkdirs() }
        for ((name, bytes) in result.fontFiles) File(fontDir, name).writeBytes(bytes)
        val drawables = File(res, "drawable").apply { mkdirs() }
        for ((name, xml) in result.drawables) File(drawables, "$name.xml").writeText(xml)
        logger.lifecycle("rdstr.display: ${result.classCount} token classes, ${result.drawables.size} icons, ${result.fontFiles.size} fonts")
    }
}

/** The generation itself, free of Gradle so the tests run it on the real sheets. */
object DisplayBuild {
    class Result(
        val tokens: String, val icons: String, val drawables: Map<String, String>, val classCount: Int,
        val fonts: String, val fontFiles: Map<String, ByteArray>,
    )

    /** The sheets `layout.tsx` imports from `../styles/`, in order, the tool's own left out. */
    fun sheetOrder(layoutTsx: String, tool: List<String>): List<String> =
        Regex("""import\s+["']\.\./styles/([\w-]+\.css)["']""").findAll(layoutTsx).map { it.groupValues[1] }.filter { it !in tool }.toList()

    fun run(
        sheets: List<Pair<String, String>>, svgs: Map<String, String>, tokensPackage: String, rPackage: String,
        fontFiles: Map<String, ByteArray>,
    ): Result {
        var order = 0
        val declarations = sheets.flatMap { (name, text) -> Css.declarations(name, text, order).also { order += it.size } }
        val tokens = Tokens(declarations)
        val classes = tokens.classes()
        val missingIcons = tokens.icons.map { it.file.removeSuffix(".svg") }.filter { it !in svgs }
        if (missingIcons.isNotEmpty()) throw DisplayBuildException("icon classes point at missing files: ${missingIcons.joinToString()}")
        val drawables = svgs.toSortedMap().map { (stem, text) -> KotlinWriter.drawable(stem) to Svg.toVector(stem, text) }.toMap()
        val faces = fontFaces(sheets, fontFiles)
        return Result(
            KotlinWriter.tokens(tokensPackage, classes),
            KotlinWriter.icons(tokensPackage, rPackage, tokens.icons, svgs.keys.toList()),
            drawables,
            classes.size,
            KotlinWriter.fonts(tokensPackage, rPackage, faces),
            faces.associate { "${it.resource}.${it.file.substringAfterLast('.')}" to fontFiles.getValue(it.file) },
        )
    }

    /** A font family the screen loads, from one file Android reads as it is. */
    data class Face(val family: String, val file: String, val weights: IntRange, val resource: String)

    /**
     * The `@font-face`s of the sheets: one per family (Android picks a face by family and weight, not by character
     * range), from a TrueType or OpenType file in `emulator/public/fonts`; the same file goes into the APK.
     */
    fun fontFaces(sheets: List<Pair<String, String>>, files: Map<String, ByteArray>): List<Face> {
        val faces = sheets.flatMap { (sheet, text) -> Css.fontFaces(text).map { sheet to it } }.map { (sheet, f) ->
            val family = f["font-family"]?.trim('"', '\'') ?: throw DisplayBuildException("$sheet: @font-face without font-family")
            if ("unicode-range" in f) throw DisplayBuildException("$sheet: $family is split by unicode-range; Android needs one file per family")
            val src = Regex("""url\(["']?/fonts/([^"')]+)["']?\)""").find(f["src"].orEmpty())?.groupValues?.get(1)
                ?: throw DisplayBuildException("$sheet: $family must load from /fonts/")
            if (!src.endsWith(".ttf") && !src.endsWith(".otf")) throw DisplayBuildException("$sheet: $family loads $src; Android 8 reads TrueType or OpenType only")
            if (src !in files) throw DisplayBuildException("$sheet: $family loads $src, missing from emulator/public/fonts")
            val w = f["font-weight"].orEmpty().split(' ').mapNotNull { it.toIntOrNull() }
            Face(family, src, (w.firstOrNull() ?: 400)..(w.lastOrNull() ?: 400), "rdstr_font_" + family.lowercase().replace(Regex("[^a-z0-9]"), "_"))
        }
        val twice = faces.groupBy { it.family }.filterValues { it.size > 1 }.keys
        if (twice.isNotEmpty()) throw DisplayBuildException("more than one @font-face for ${twice.joinToString()}")
        return faces
    }
}
