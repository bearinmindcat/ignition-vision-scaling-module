val sdkVersion: String by rootProject.extra

dependencies {
    compileOnly("com.inductiveautomation.ignitionsdk:ignition-common:$sdkVersion")
    compileOnly("com.inductiveautomation.ignitionsdk:client-api:$sdkVersion")
    compileOnly("com.inductiveautomation.ignitionsdk:vision-client-api:$sdkVersion")
}
