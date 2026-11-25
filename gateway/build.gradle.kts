val sdkVersion: String by rootProject.extra

dependencies {
    compileOnly("com.inductiveautomation.ignitionsdk:ignition-common:$sdkVersion")
    compileOnly("com.inductiveautomation.ignitionsdk:gateway-api:$sdkVersion")
}
