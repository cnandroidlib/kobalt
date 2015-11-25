package com.beust.kobalt.api

/**
 * Plugins that add compiler flags.
 */
interface ICompilerFlagContributor : IContributor {
    fun flagsFor(project: Project, currentFlags: List<String>): List<String>
}
