package ro.rdstr.gradle.apk

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
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.testing.Test
import java.io.File

/**
 * `rdstr.apk`: builds the display app's design from the browser emulator. The sheets the emulator loads (in the
 * order `emulator/app/layout.tsx` imports them) give the token classes, its icons the VectorDrawables. Both are
 * generated sources of the Android module that applies the plugin, rebuilt only when the CSS or the icons change.
 */
class ApkPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val emulator = project.rootProject.layout.projectDirectory.dir("emulator")
        val task = project.tasks.register("generateApkUi", GenerateApkUi::class.java) {
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
        val root = project.rootProject.layout.projectDirectory
        val check = project.tasks.register("checkApkLogic", CheckApkLogic::class.java) {
            group = "verification"
            description = "Checks that the display app implements every piece of logic the emulator's screen uses."
            components.from(emulator.dir("components").asFileTree.matching { include("*.tsx", "*.ts") })
            car.from(emulator.dir("car").asFileTree.matching { include("*.ts") })
            coreCar.from(root.dir("core-car/src/commonMain/kotlin").asFileTree.matching { include("**/*.kt") })
            android.from(root.dir("app/src/main/kotlin").asFileTree.matching { include("**/*.kt") })
            android.from(project.layout.projectDirectory.dir("src/main/kotlin").asFileTree.matching { include("**/*.kt") })
            // strict under build-apk, the whole suite
            val suite = project.gradle.startParameter.taskNames.any { it.substringAfterLast(':') == "build-apk" }
            strict.set(project.providers.gradleProperty("rdstr.apk.strict").map { it.toBoolean() }.orElse(suite))
            report.set(project.layout.buildDirectory.file("reports/rdstr/apk-logic.md"))
            base.set(root)
        }
        project.tasks.matching { it.name == "test" || it.name == "check" }.configureEach { dependsOn(check) }
        // visual parity: the browser capture (npm run parity:capture) in build/parity/web, the module's unit tests
        // write the Android one into build/parity/android, compareParity reports into build/reports/parity
        val parity = project.rootProject.layout.buildDirectory.dir("parity")
        val property = { name: String -> project.providers.gradleProperty("rdstr.parity.$name") }
        // the browser side, from the running emulator (npm run dev, or npm start of a build); build-apk runs it first
        val capture = project.tasks.register("captureParity", Exec::class.java) {
            group = "verification"
            description = "Captures the browser emulator's parity scenes into build/parity/web (the emulator must be running)."
            workingDir = root.asFile
            val npm = if (System.getProperty("os.name").startsWith("Windows")) listOf("cmd", "/c", "npm") else listOf("npm")
            val channel = property("channel").orNull?.let { listOf("--channel", it) }.orEmpty()
            commandLine(npm + listOf("run", "parity:capture", "--", "--base", property("base").getOrElse("http://localhost:8787")) + channel)
        }
        project.tasks.withType(Test::class.java).configureEach {
            mustRunAfter(capture)
            systemProperty("rdstr.parity.web", parity.get().dir("web").asFile.path)
            systemProperty("rdstr.parity.android", parity.get().dir("android").asFile.path)
            inputs.files(project.fileTree(parity.map { it.dir("web") })).withPropertyName("parityWeb").withPathSensitivity(PathSensitivity.RELATIVE)
            outputs.dir(parity.map { it.dir("android") }).withPropertyName("parityAndroid")
        }
        project.tasks.register("compareParity", CompareParity::class.java) {
            group = "verification"
            description = "Compares the display app's views with the browser emulator, element by element and pixel by pixel."
            web.set(parity.map { it.dir("web") })
            android.set(parity.map { it.dir("android") })
            report.set(project.rootProject.layout.buildDirectory.dir("reports/parity"))
            val limits = Parity.Limits()
            tolerance.set(property("tolerance").map { it.toInt() }.orElse(limits.tolerance))
            edgeTolerance.set(property("edgeTolerance").map { it.toInt() }.orElse(limits.edgeTolerance))
            maxPercent.set(property("maxPercent").map { it.toDouble() }.orElse(limits.maxPercent))
            baseline.set(project.layout.projectDirectory.file("parity-baseline.json"))
            margin.set(property("margin").map { it.toDouble() }.orElse(0.1))
            updateBaseline.set(property("updateBaseline").map { it.toBoolean() }.orElse(false))
            dependsOn("testDebugUnitTest")
            mustRunAfter(capture)
        }
        project.extensions.getByType(AndroidComponentsExtension::class.java).onVariants { v ->
            v.sources.kotlin?.addGeneratedSourceDirectory(task, GenerateApkUi::kotlinOut)
            v.sources.res?.addGeneratedSourceDirectory(task, GenerateApkUi::resOut)
        }
    }
}

@CacheableTask
abstract class GenerateApkUi : DefaultTask() {
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
        val order = ApkBuild.sheetOrder(layout.get().asFile.readText(), tool.get())
        val missing = order.filter { it !in sheets }
        if (missing.isNotEmpty()) throw ApkBuildException("layout.tsx imports ${missing.joinToString()} which is not in emulator/styles")
        val unused = sheets.keys - order.toSet() - tool.get().toSet()
        if (unused.isNotEmpty()) throw ApkBuildException("emulator/styles has ${unused.joinToString()} which layout.tsx does not import")
        val result = ApkBuild.run(order.map { it to sheets.getValue(it).readText() }, icons.files.associate { it.nameWithoutExtension to it.readText() },
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
        logger.lifecycle("rdstr.apk: ${result.classCount} token classes, ${result.drawables.size} icons, ${result.fontFiles.size} fonts")
    }
}

@CacheableTask
abstract class CheckApkLogic : DefaultTask() {
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE) abstract val components: ConfigurableFileCollection
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE) abstract val car: ConfigurableFileCollection
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE) abstract val coreCar: ConfigurableFileCollection
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE) abstract val android: ConfigurableFileCollection
    /** Every gap fails, not only those in components that already have a view. */
    @get:Input abstract val strict: Property<Boolean>
    @get:OutputFile abstract val report: RegularFileProperty
    /** The repository, for paths in the report. */
    @get:Internal abstract val base: DirectoryProperty

    @TaskAction
    fun check() {
        val root = base.get().asFile
        val result = LogicCheck.run(
            components.files.associate { it.name to it.readText() },
            car.files.associate { it.nameWithoutExtension to it.readText() },
            coreCar.files.map { it.readText() },
            android.files.associate { it.relativeTo(root).invariantSeparatorsPath to it.readText() },
        )
        report.get().asFile.apply { parentFile.mkdirs() }.writeText(result.markdown())
        val failures = result.failures(strict.get())
        val logic = result.uses.map { it.key }.distinct()
        logger.lifecycle("rdstr.apk: ${result.screen.size - result.missingViews.size}/${result.screen.size} screen files mirrored, " +
            "${logic.size - result.missingLogic.map { it.key }.distinct().size}/${logic.size} logic values implemented; report ${report.get().asFile}")
        if (failures.isNotEmpty()) throw ApkBuildException("logic gaps:" + failures.joinToString("") { "\n  $it" })
    }
}

abstract class CompareParity : DefaultTask() {
    @get:Internal abstract val web: DirectoryProperty
    @get:Internal abstract val android: DirectoryProperty
    @get:Internal abstract val report: DirectoryProperty
    /** [Parity.Limits], from the Gradle properties rdstr.parity.tolerance, .edgeTolerance and .maxPercent. */
    @get:Input abstract val tolerance: Property<Int>
    @get:Input abstract val edgeTolerance: Property<Int>
    @get:Input abstract val maxPercent: Property<Double>
    /** The accepted state of each scene; a scene fails only when it gets worse ([Parity.Accepted]). */
    @get:Internal abstract val baseline: RegularFileProperty
    /** Percentage points a scene's pixel share may go over its baseline (renderers of other systems differ slightly). */
    @get:Input abstract val margin: Property<Double>
    /** rdstr.parity.updateBaseline=true: accept this run as the baseline instead of failing. */
    @get:Input abstract val updateBaseline: Property<Boolean>

    init {
        // the captures change outside Gradle (the browser side is a Node script): always compare
        outputs.upToDateWhen { false }
    }

    @TaskAction
    fun compare() {
        val dir = report.get().asFile
        val file = baseline.get().asFile
        val accepted = if (file.isFile && !updateBaseline.get()) Parity.readBaseline(file.readText()) else emptyMap()
        val result = Parity.run(web.get().asFile, android.get().asFile, dir, Parity.Limits(tolerance.get(), edgeTolerance.get(), maxPercent.get()), accepted, margin.get())
        logger.lifecycle("rdstr.apk: ${result.scenes.count { it.failures.isEmpty() }}/${result.scenes.size} parity scenes pass, " +
            "${result.notBuilt.size} browser scenes without an Android one; report ${File(dir, "index.md")}")
        if (result.scenes.isEmpty()) throw ApkBuildException("no Android parity capture in ${android.get().asFile}; run npm run parity:capture first")
        if (updateBaseline.get()) {
            File(dir, "baseline.json").copyTo(file, overwrite = true)
            logger.lifecycle("rdstr.apk: baseline updated, $file")
            return
        }
        if (result.failures.isNotEmpty()) throw ApkBuildException("visual parity:" + result.failures.joinToString("") { "\n  $it" })
    }
}

/** The generation itself, free of Gradle so the tests run it on the real sheets. */
object ApkBuild {
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
        if (missingIcons.isNotEmpty()) throw ApkBuildException("icon classes point at missing files: ${missingIcons.joinToString()}")
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
            val family = f["font-family"]?.trim('"', '\'') ?: throw ApkBuildException("$sheet: @font-face without font-family")
            if ("unicode-range" in f) throw ApkBuildException("$sheet: $family is split by unicode-range; Android needs one file per family")
            val src = Regex("""url\(["']?/fonts/([^"')]+)["']?\)""").find(f["src"].orEmpty())?.groupValues?.get(1)
                ?: throw ApkBuildException("$sheet: $family must load from /fonts/")
            if (!src.endsWith(".ttf") && !src.endsWith(".otf")) throw ApkBuildException("$sheet: $family loads $src; Android 8 reads TrueType or OpenType only")
            if (src !in files) throw ApkBuildException("$sheet: $family loads $src, missing from emulator/public/fonts")
            val w = f["font-weight"].orEmpty().split(' ').mapNotNull { it.toIntOrNull() }
            Face(family, src, (w.firstOrNull() ?: 400)..(w.lastOrNull() ?: 400), "rdstr_font_" + family.lowercase().replace(Regex("[^a-z0-9]"), "_"))
        }
        val twice = faces.groupBy { it.family }.filterValues { it.size > 1 }.keys
        if (twice.isNotEmpty()) throw ApkBuildException("more than one @font-face for ${twice.joinToString()}")
        return faces
    }
}
