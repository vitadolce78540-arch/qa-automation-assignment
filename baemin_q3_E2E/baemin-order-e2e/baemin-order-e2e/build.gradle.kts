plugins {
    java
}

repositories {
    mavenCentral()
}

dependencies {
    testImplementation("junit:junit:4.13.2")              // osc_e2e 저장소와 같은 JUnit 4
    testImplementation("io.appium:java-client:9.3.0")     // Selenium 4 포함 (W3C 프로토콜)
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 17
    options.encoding = "UTF-8"
}

// -P 로 넘긴 값을 테스트 JVM 시스템 프로퍼티로 전달
//   ./e2e_automation test -Pudid=R5KL402G4WV -Ptimeout.scale=1.5
//   (Windows: e2e_automation.bat test -Pudid=R5KL402G4WV)
fun Test.passThrough(vararg keys: String) =
    keys.forEach { key -> providers.gradleProperty(key).orNull?.let { systemProperty(key, it) } }

tasks.test {
    passThrough("appium.server", "udid", "app.package", "app.activity", "timeout.scale",
        "artifacts.dir", "result.csv", "cleanup.cart")
    systemProperty("file.encoding", "UTF-8")
    outputs.upToDateWhen { false }
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
