plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.openapi.generator)
}

kotlin { jvmToolchain(17) }

/**
 * Generates the API client from the contract.
 *
 * Named `generateApiClient` because contract.sh invokes it by that name; the
 * plugin's own task is `openApiGenerate`. The output is generated into the
 * source tree rather than into `build/`, and committed, so that `contract.sh`
 * can diff it: a client that lives only in `build/` cannot drift visibly, and a
 * drift check that cannot fail is not a check.
 */
val generateApiClient by tasks.registering(org.openapitools.generator.gradle.plugin.tasks.GenerateTask::class) {
    // The generator writes over files but never removes ones it no longer
    // produces. A renamed schema would leave its old model behind, committed and
    // compiling, and contract.sh would see no drift. Clearing the directory
    // first makes the output a function of the spec alone.
    doFirst { delete(layout.projectDirectory.dir("generated")) }

    generatorName.set("kotlin")
    inputSpec.set(rootProject.file("../artifacts/openapi.json").absolutePath)
    outputDir.set(layout.projectDirectory.dir("generated").asFile.absolutePath)
    apiPackage.set("com.cryptum.api")
    modelPackage.set("com.cryptum.api.model")
    packageName.set("com.cryptum.api")

    configOptions.set(
        mapOf(
            // Ktor + kotlinx.serialization: Kotlin-first the whole way down, and
            // no runtime reflection, which matters on Android where reflection
            // costs startup time and drags in keep rules.
            "library" to "jvm-ktor",
            "serializationLibrary" to "kotlinx_serialization",
            "dateLibrary" to "kotlinx-datetime",
            // The default emits an `enumUnknownDefaultCase` and a mutable
            // builder surface neither of which this contract needs.
            "omitGradleWrapper" to "true",
        ),
    )

    // `format: byte` maps to kotlin.ByteArray, and kotlinx.serialization encodes
    // a bare ByteArray as a JSON array of integers — not the base64 string the
    // contract specifies. That mismatch compiles, type-checks, and produces a
    // client that silently cannot decrypt anything, so the type is redirected to
    // an alias that carries a base64 serializer with it.
    typeMappings.set(mapOf("ByteArray" to "Base64Bytes", "UUID" to "SerializableUuid"))
    importMappings.set(
        mapOf(
            "Base64Bytes" to "com.cryptum.api.Base64Bytes",
            "SerializableUuid" to "com.cryptum.api.SerializableUuid",
        ),
    )

    // Docs and the generated test scaffold would be committed and then diffed by
    // contract.sh — noise around the only thing that matters. Supporting files
    // are NOT excluded: the Ktor infrastructure classes are what the generated
    // APIs are written against.
    // The generator also ships a standalone Gradle project alongside the
    // sources — its own build.gradle, settings.gradle and README. A second
    // settings.gradle inside an existing Gradle build confuses the IDE and
    // anyone reading the tree, and none of it is the contract. ignoreFileOverride
    // does not suppress these, so they are removed after the fact.
    doLast {
        delete(
            layout.projectDirectory.file("generated/build.gradle"),
            layout.projectDirectory.file("generated/settings.gradle"),
            layout.projectDirectory.file("generated/README.md"),
        )
    }

    globalProperties.set(
        mapOf(
            "modelDocs" to "false",
            "apiDocs" to "false",
            "modelTests" to "false",
            "apiTests" to "false",
        ),
    )
}

kotlin.sourceSets["main"].kotlin.srcDir(layout.projectDirectory.dir("generated/src/main/kotlin"))

tasks.named("compileKotlin") { dependsOn(generateApiClient) }

dependencies {
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.datetime)
    testImplementation(kotlin("test"))
}

tasks.test { useJUnitPlatform() }
