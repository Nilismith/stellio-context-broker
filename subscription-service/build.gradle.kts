import com.google.cloud.tools.jib.gradle.PlatformParameters

configurations {
    compileOnly {
        extendsFrom(configurations.annotationProcessor.get())
    }
}

plugins {
    id("com.google.cloud.tools.jib")
    id("org.springframework.boot")
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-data-r2dbc")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-client")
    // required for Flyway's direct access to the DB to apply migration scripts
    implementation("org.springframework:spring-jdbc")
    implementation("org.flywaydb:flyway-core")
    implementation("org.postgresql:r2dbc-postgresql")
    implementation("com.jayway.jsonpath:json-path:2.9.0")
    implementation(project(":shared"))

    detektPlugins("io.gitlab.arturbosch.detekt:detekt-formatting:1.23.5")

    developmentOnly("org.springframework.boot:spring-boot-devtools")

    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.wiremock:wiremock-standalone:3.3.1")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.testcontainers:r2dbc")
    testImplementation(testFixtures(project(":shared")))
    // https://docs.gradle.org/8.4/userguide/upgrading_version_8.html#test_framework_implementation_dependencies
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

defaultTasks("bootRun")

tasks.bootRun {
    environment("SPRING_PROFILES_ACTIVE", "dev")
}

jib.from.image = project.ext["jibFromImage"].toString()
jib.from.platforms.addAll(project.ext["jibFromPlatforms"] as List<PlatformParameters>)
jib.to.image = "stellio/stellio-subscription-service:${project.version}"
jib.pluginExtensions {
    pluginExtension {
        implementation = "com.google.cloud.tools.jib.gradle.extension.springboot.JibSpringBootExtension"
    }
}
jib.container.ports = listOf("8084")
jib.container.creationTime.set(project.ext["jibContainerCreationTime"].toString())
jib.container.labels.putAll(project.ext["jibContainerLabels"] as Map<String, String>)
