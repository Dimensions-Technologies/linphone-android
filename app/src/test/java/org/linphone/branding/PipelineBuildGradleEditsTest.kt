package org.linphone.branding

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pipelines rewrite app/build.gradle with sed before building (package id, version code and
 * version name). sed exits 0 when its pattern matches nothing, so a reworded line in build.gradle
 * would silently ship the default package id or version. This checks every such edit still matches.
 */
class PipelineBuildGradleEditsTest {

    private val appDir: File = listOf(File(System.getProperty("user.dir")!!), File("app"))
        .map { it.absoluteFile }
        .first { File(it, "src/main/AndroidManifest.xml").exists() }
    private val repoDir: File = appDir.parentFile!!

    private data class Edit(val pipeline: String, val pattern: String)

    // Every "sed -i 's/<pattern>/.../g'" run on build.gradle, with YAML's \" unescaped.
    private val edits: List<Edit> by lazy {
        repoDir.listFiles { f -> f.name.matches(Regex("build-.+\\.yml")) }!!
            .sortedBy { it.name }
            .flatMap { yml ->
                yml.readLines()
                    .filter { "build.gradle" in it && "sed -i" in it }
                    .map { line ->
                        val pattern = Regex("""sed -i ["']s/(.+?)/""").find(line.replace("\\\"", "\""))
                            ?.groupValues?.get(1)
                            ?: throw AssertionError("${yml.name}: can't parse sed command: $line")
                        Edit(yml.name, pattern)
                    }
            }
    }

    @Test
    fun `pipelines edit build gradle`() {
        // Guards the parsing above: if it found nothing, the check below would pass vacuously.
        val pipelines = edits.map { it.pipeline }.toSet()
        assertTrue("No build.gradle edits found in any pipeline", edits.isNotEmpty())
        assertTrue(
            "Expected build-ci.yml and every build-release*.yml to edit build.gradle, found $pipelines",
            pipelines.containsAll(listOf("build-ci.yml", "build-release.yml"))
        )
    }

    @Test
    fun `every pipeline edit to build gradle still matches`() {
        val buildGradle = File(appDir, "build.gradle").readText()
        // The patterns are plain text. In sed's basic regex only "." is special among the
        // characters they use, and it matches itself too, so a literal search is equivalent.
        val unmatched = edits.filterNot { it.pattern in buildGradle }
            .map { "${it.pipeline}: s/${it.pattern}/" }
        assertTrue(
            "These pipeline edits no longer match app/build.gradle, so CI would skip them:\n" +
                unmatched.joinToString("\n"),
            unmatched.isEmpty()
        )
    }
}
