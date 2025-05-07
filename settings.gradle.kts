rootProject.name = "MVPFunctions"

includeBuild("../open-runtimes/runtimes/kotlin/versions/latest") {
    // this becomes the project path of the included build:
    name = "runtimes-kotlin"

    // *this* is the magic that actually swaps the Maven module
    dependencySubstitution {
        substitute(
            module("com.github.open-runtimes.open-runtimes:runtimes-kotlin")
        ).using(
            // the root project of that included build
            project(":")
        )
    }
}