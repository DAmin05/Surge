rootProject.name = "surge"

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories { mavenCentral() }
}

include(
    ":libs:contracts",
    ":services:admission",
    ":services:inventory",
    ":services:order",
)
