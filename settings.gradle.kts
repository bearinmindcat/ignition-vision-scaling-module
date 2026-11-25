pluginManagement {
    repositories {
        gradlePluginPortal()
        // The modl plugin depends on IA's module-signer, which only lives here.
        maven { url = uri("https://nexus.inductiveautomation.com/repository/public/") }
    }
}

rootProject.name = "ignition-vision-scaling-module"

include(":client", ":gateway")
