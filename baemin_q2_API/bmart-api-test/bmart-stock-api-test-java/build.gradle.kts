import org.gradle.api.tasks.testing.logging.TestExceptionFormat

plugins {
    java
}

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    testImplementation("io.rest-assured:rest-assured:5.5.0")
    testImplementation("com.fasterxml.jackson.core:jackson-databind:2.18.2")  // RestAssured 요청 본문(Map → JSON) 직렬화
    testImplementation("org.wiremock:wiremock-standalone:3.13.2")             // 의존성이 shading 되어 충돌이 적은 standalone 사용
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 17
    options.encoding = "UTF-8"
}

// -P 로 넘긴 값을 테스트 JVM 의 시스템 프로퍼티로 전달
//   ./gradlew test -Pbmart.baseUrl=https://qa-api.example.com   실서버 대상 실행
//   ./gradlew test -Pstub.unsafe=true                           결함 모드 스텁으로 실행
//   ./gradlew test -Pstub.delayMs=50                            결제 지연 변경
fun Test.passThrough(vararg keys: String) =
    keys.forEach { key -> providers.gradleProperty(key).orNull?.let { systemProperty(key, it) } }

tasks.test {
    useJUnitPlatform()
    passThrough("bmart.baseUrl", "stub.unsafe", "stub.delayMs")
    systemProperty("file.encoding", "UTF-8")
    outputs.upToDateWhen { false }       // 동시성 테스트는 매번 다시 실행
    testLogging {
        events("passed", "failed", "skipped")
        exceptionFormat = TestExceptionFormat.FULL
    }
}

// 동시성 테스트만: ./gradlew concurrencyTest
tasks.register<Test>("concurrencyTest") {
    description = "동시성 테스트(@Tag(\"concurrency\"))만 실행"
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform { includeTags("concurrency") }
    passThrough("bmart.baseUrl", "stub.unsafe", "stub.delayMs")
    outputs.upToDateWhen { false }
    testLogging {
        events("passed", "failed")
        exceptionFormat = TestExceptionFormat.FULL
    }
}

// 스텁 서버만 단독 실행: ./gradlew runStub [-Pport=8080] [-Pstub.unsafe=true]
tasks.register<JavaExec>("runStub") {
    description = "WireMock 재고 스텁 서버를 단독으로 실행"
    group = "application"
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "bmart.stock.stub.StockStubMain"
    val port = providers.gradleProperty("port").getOrElse("8080")
    args("--port", port, "--delay-ms", providers.gradleProperty("stub.delayMs").getOrElse("30"))
    if (providers.gradleProperty("stub.unsafe").orNull == "true") args("--unsafe")
}
