plugins {
    id("io.ia.sdk.modl") version "0.5.0"
}

// Ignition SDK version to compile against. Must not be newer than the target Gateway.
val sdkVersion by extra("8.1.35")

allprojects {
    group = "io.github.bearinmindcat.visionscaling"
}

subprojects {
    apply(plugin = "java-library")

    repositories {
        mavenCentral()
        maven { url = uri("https://nexus.inductiveautomation.com/repository/public/") }
    }

    // The 8.1.35 SDK is published as Java 17 libraries (Gateways and clients on this version run Java 17).
    tasks.withType<JavaCompile>().configureEach {
        options.release.set(17)
        options.encoding = "UTF-8"
    }
}

ignitionModule {
    name.set("Vision Scaling Fix")
    fileName.set("Vision-Scaling-Fix.modl")
    id.set("io.github.bearinmindcat.visionscaling")
    moduleVersion.set("${project.version}")
    moduleDescription.set("Keeps numbers and labels whole when a Vision client window is smaller than its design size.")
    requiredIgnitionVersion.set(sdkVersion)
    requiredFrameworkVersion.set("8")
    freeModule.set(true)

    // Vision clients only: the Designer keeps Vision's own behavior while windows are edited.
    projectScopes.putAll(
        mapOf(
            ":client" to "C",
            ":gateway" to "G",
        )
    )

    // Depend on Vision so our classloader can see Vision's classes in clients (Vision 8's module ID).
    moduleDependencySpecs {
        register("com.inductiveautomation.vision") {
            scope = "C"
            required = true
        }
    }

    hooks.putAll(
        mapOf(
            "io.github.bearinmindcat.visionscaling.client.ClientHook" to "C",
            "io.github.bearinmindcat.visionscaling.gateway.GatewayHook" to "G",
        )
    )

    // Signs when the ignition.signing.* properties are set (e.g. in ~/.gradle/gradle.properties);
    // otherwise builds an unsigned module, which needs -Dignition.allowunsignedmodules=true on the Gateway.
    skipModlSigning.set((findProperty("ignition.signing.keystoreFile") as String?).isNullOrBlank())
}
