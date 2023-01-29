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
    implementation("org.springframework.boot:spring-boot-starter-data-neo4j")
    implementation("eu.michael-simons.neo4j:neo4j-migrations-spring-boot-starter:1.15.0")
    implementation("org.springframework.boot:spring-boot-starter-cache")
    implementation(project(":shared"))

    detektPlugins("io.gitlab.arturbosch.detekt:detekt-formatting:1.21.0")

    developmentOnly("org.springframework.boot:spring-boot-devtools")

    testImplementation("org.testcontainers:neo4j")
    testImplementation(testFixtures(project(":shared")))
}

defaultTasks("bootRun")

tasks.bootRun {
    environment("SPRING_PROFILES_ACTIVE", "dev")
}

jib.from.image = project.ext["jibFromImage"].toString()
jib.from.platforms.addAll(project.ext["jibFromPlatforms"] as List<PlatformParameters>)
jib.to.image = "stellio/stellio-entity-service:${project.version}"
jib.pluginExtensions {
    pluginExtension {
        implementation = "com.google.cloud.tools.jib.gradle.extension.springboot.JibSpringBootExtension"
    }
}
jib.container.jvmFlags = listOf("-Xms512m", "-Xmx1024m")
jib.container.ports = listOf("8082")
jib.container.creationTime.set(project.ext["jibContainerCreationTime"].toString())
jib.container.labels.putAll(project.ext["jibContainerLabels"] as Map<String, String>)
