pluginManagement {
    repositories {
        google()
        val mirror = System.getenv("DOWNLOADER_MAVEN_CENTRAL")
        if (mirror == null) mavenCentral() else maven { url = uri(mirror) }
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        val mirror = System.getenv("DOWNLOADER_MAVEN_CENTRAL")
        if (mirror == null) mavenCentral() else maven { url = uri(mirror) }
    }
}
rootProject.name = "Downloader"
include(":app")
