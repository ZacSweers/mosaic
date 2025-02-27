package com.jakewharton.mosaic.gradle

import javax.inject.Inject
import org.gradle.api.Project
import org.gradle.api.model.ObjectFactory
import org.gradle.api.plugins.JavaPlugin.API_CONFIGURATION_NAME
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilerPluginSupportPlugin
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType
import org.jetbrains.kotlin.gradle.plugin.KotlinSourceSet.Companion.COMMON_MAIN_SOURCE_SET_NAME
import org.jetbrains.kotlin.gradle.plugin.SubpluginArtifact
import org.jetbrains.kotlin.gradle.plugin.SubpluginOption

private open class MosaicExtensionImpl
@Inject constructor(objectFactory: ObjectFactory) : MosaicExtension {
	override val kotlinCompilerPlugin: Property<String> =
		objectFactory.property(String::class.java)
			.convention(composeCompilerVersion)
}

private const val extensionName = "mosaic"

@Suppress("unused") // Used reflectively by Gradle.
class MosaicPlugin : KotlinCompilerPluginSupportPlugin {
	private lateinit var extension: MosaicExtension

	override fun apply(target: Project) {
		super.apply(target)

		extension = target.extensions.create(
			MosaicExtension::class.java,
			extensionName,
			MosaicExtensionImpl::class.java,
		)

		if (target.isInternal() && target.path == ":mosaic-runtime") {
			// Being lazy and using our own plugin to configure the Compose compiler on our runtime.
			// Bail out because otherwise we create a circular dependency reference on ourselves!
			return
		}

		target.afterEvaluate {
			val multiplatform = target.extensions.findByType(KotlinMultiplatformExtension::class.java)
			val jvm = target.extensions.findByType(KotlinJvmProjectExtension::class.java)

			val dependency: Any = if (target.isInternal()) {
				target.dependencies.project(mapOf("path" to ":mosaic-runtime"))
			} else {
				"com.jakewharton.mosaic:mosaic-runtime:$mosaicVersion"
			}

			if (jvm != null) {
				target.dependencies.add(API_CONFIGURATION_NAME, dependency)
			} else if (multiplatform != null) {
				multiplatform.sourceSets.getByName(COMMON_MAIN_SOURCE_SET_NAME) { sourceSet ->
					sourceSet.dependencies {
						api(dependency)
					}
				}
			} else {
				throw IllegalStateException("Kotlin/JVM or Kotlin/Multiplatform plugin must be applied.")
			}
		}
	}

	override fun isApplicable(kotlinCompilation: KotlinCompilation<*>) = true

	override fun getCompilerPluginId() = "com.jakewharton.mosaic"

	override fun getPluginArtifact(): SubpluginArtifact {
		val plugin = extension.kotlinCompilerPlugin.get()
		val parts = plugin.split(":")
		return when (parts.size) {
			1 -> SubpluginArtifact("org.jetbrains.compose.compiler", "compiler", parts[0])
			3 -> SubpluginArtifact(parts[0], parts[1], parts[2])
			else -> error(
				"""
        |Illegal format of '$extensionName.${MosaicExtension::kotlinCompilerPlugin.name}' property.
        |Expected format: either '<VERSION>' or '<GROUP_ID>:<ARTIFACT_ID>:<VERSION>'
        |Actual value: '$plugin'
        """.trimMargin(),
			)
		}
	}

	override fun applyToCompilation(kotlinCompilation: KotlinCompilation<*>): Provider<List<SubpluginOption>> {
		if (kotlinCompilation.target.platformType == KotlinPlatformType.js) {
			// This enables a workaround for Compose lambda generation to function correctly in JS.
			// Note: We cannot use SubpluginOption to do this because it targets the Compose plugin.
			kotlinCompilation.compilerOptions.options.freeCompilerArgs.addAll(
				"-P",
				"plugin:androidx.compose.compiler.plugins.kotlin:generateDecoys=true",
			)
		}

		return kotlinCompilation.target.project.provider { emptyList() }
	}

	private fun Project.isInternal(): Boolean {
		return properties["com.jakewharton.mosaic.internal"].toString() == "true"
	}
}
