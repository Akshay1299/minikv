plugins {
    java
    application
}

group = "dev.akshay"
version = "0.1.0"

java {
    toolchain {
        // Bump to 21 once a JDK 21 toolchain is available; the code only uses 17 features.
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.10.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
}

application {
    mainClass.set("dev.akshay.minikv.cli.MiniKvCli")
}

tasks.register<JavaExec>("demo") {
    group = "application"
    description = "Run the narrated MiniKV walkthrough."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("dev.akshay.minikv.demo.Demo")
}
