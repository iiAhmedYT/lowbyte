plugins {
    kotlin("jvm") version "2.1.21" apply false
}

/**
 * Nothing is built here. The root holds what both modules agree on and no more,
 * so that adding a third frontend is a directory and an `include`.
 */
allprojects {
    group = "dev.iiahmed"
    version = "1.0.0"

    repositories {
        mavenCentral()
    }
}
