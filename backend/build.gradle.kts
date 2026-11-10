plugins {
    kotlin("jvm")
    kotlin("plugin.spring")
    id("org.springframework.boot")
}
repositories { mavenCentral() }
kotlin { jvmToolchain(21) }
dependencies {
    implementation(platform("org.springframework.boot:spring-boot-dependencies:3.5.16"))
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-client")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.yaml:snakeyaml")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
tasks.test {
    useJUnitPlatform()
    // These integration tests use a fresh database created by scripts/check.sh.
    outputs.upToDateWhen { false }
}
tasks.bootJar { archiveFileName.set("infra-hub.jar") }
tasks.processResources {
    from(rootProject.file("deploy/runner")) {
        include("build-aukni-web.py", "bundle-web.py", "bundle-android.py", "local-nginx-test.py", "run-build")
        into("runner-scripts")
    }
}
