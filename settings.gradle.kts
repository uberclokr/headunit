pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google(); mavenCentral()
        maven("https://jitpack.io") // usb-serial-for-android, UVC libs
        flatDir { dirs("app/libs") } // spotify-app-remote AAR (manual download)
    }
}
rootProject.name = "Helm"
include(":app")
