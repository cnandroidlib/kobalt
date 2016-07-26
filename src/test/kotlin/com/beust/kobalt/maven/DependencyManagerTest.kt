package com.beust.kobalt.maven

import com.beust.kobalt.Args
import com.beust.kobalt.BaseTest
import com.beust.kobalt.TestModule
import com.beust.kobalt.api.IClasspathDependency
import com.beust.kobalt.api.Kobalt
import com.beust.kobalt.app.BuildFileCompiler
import com.beust.kobalt.maven.aether.KobaltAether
import com.beust.kobalt.maven.aether.Scope
import com.google.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.testng.annotations.Guice
import org.testng.annotations.Test

@Guice(modules = arrayOf(TestModule::class))
class DependencyManagerTest @Inject constructor(val dependencyManager: DependencyManager,
        val compilerFactory: BuildFileCompiler.IFactory, override val aether: KobaltAether) : BaseTest(aether) {

    private fun assertContains(dependencies: List<IClasspathDependency>, vararg ids: String) {
        ids.forEach { id ->
            assertThat(dependencies.any { it.id.contains(id) }).isTrue()
        }
    }

    @Test(description = "Make sure that COMPILE scope dependencies get resolved properly")
    fun testScopeDependenciesShouldBeDownloaded() {
        val testDeps = listOf(dependencyManager.create("org.testng:testng:6.9.11"))

        // Should only resolve to TestNG
        dependencyManager.transitiveClosure(testDeps, listOf(Scope.COMPILE)).let { dependencies ->
            assertThat(dependencies.any { it.id.contains(":jcommander:") }).isFalse()
            assertContains(dependencies, ":testng:")
        }

        // Should resolve to TestNG and its dependencies
        dependencyManager.transitiveClosure(testDeps, listOf(Scope.TEST)).let { dependencies ->
            assertContains(dependencies, ":jcommander:")
            assertContains(dependencies, ":bsh:")
            assertContains(dependencies, ":ant:")
            assertContains(dependencies, ":ant-launcher:")
            assertContains(dependencies, ":testng:")
        }

    }

    @Test
    fun honorRuntimeDependenciesBetweenProjects() {
        val buildFileString = """
            import com.beust.kobalt.*

            val lib = project {
                name = "lib"
                dependencies {
                    compile("org.testng:testng:6.9.11")
                    runtime("com.beust:jcommander:1.48")
                }
            }

            val p = project(lib) {
                name = "transitive"
            }
        """

        val compileResult = compileBuildFile(buildFileString, Args(), compilerFactory)
        val project2 = compileResult.projects[1]
        val dependencies = dependencyManager.calculateDependencies(project2, Kobalt.context!!,
                listOf(Scope.COMPILE, Scope.RUNTIME),
                project2.compileDependencies + project2.compileRuntimeDependencies)
        assertContains(dependencies, ":testng:")
        assertContains(dependencies, ":jcommander:")
    }
}