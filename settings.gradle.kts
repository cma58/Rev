pluginManagement {
    repositories {
        google()
        // repo.maven.apache.org rate-limits shared egress IPs with HTTP 429;
        // repo1.maven.org is the same index on a host the proxy passes through.
        maven { url = uri("https://repo1.maven.org/maven2") }
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        maven { url = uri("https://repo1.maven.org/maven2") }
    }
}
rootProject.name = "Rev"
include(":app")
