plugins {
    kotlin("jvm") version "2.3.21"
    kotlin("plugin.spring") version "2.3.21"
    id("org.springframework.boot") version "4.1.0"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "org.example"
version = "0.0.1-SNAPSHOT"
description = "demo"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-h2console")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-data-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-restclient")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("tools.jackson.module:jackson-module-kotlin")

    // 5단계~. blocking MVC 에서의 코루틴.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core")
    // 6단계. suspend 컨트롤러. Spring MVC 는 CoroutinesUtils 를 통해 suspend 함수를
    // Mono 로 변환해 async 처리하므로 reactor 브리지가 런타임에 필요하다.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor")
    // 7단계 도입부. MDC 를 코루틴으로 명시적 전파 (MDCContext)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-slf4j")
    // 7단계. ThreadLocalAccessor 기반 컨텍스트 자동 전파
    // (ContextPropagatingTaskDecorator, CoroutinesUtils.PropagationContextElement 가 사용한다)
    implementation("io.micrometer:context-propagation")

    runtimeOnly("com.h2database:h2")
    testImplementation("org.springframework.boot:spring-boot-starter-actuator-test")
    testImplementation("org.springframework.boot:spring-boot-starter-data-jdbc-test")
    testImplementation("org.springframework.boot:spring-boot-starter-restclient-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
    }
}

// 12단계에서 쓰는 java.util.concurrent.StructuredTaskScope 는 JDK 25 에서 아직 preview 다. (JEP 505)
// 컴파일과 실행 양쪽에 --enable-preview 가 필요하다.
// bootJar 로 말아서 실행할 때도 `java --enable-preview -jar ...` 로 띄워야 한다.
tasks.withType<JavaCompile> {
    options.compilerArgs.add("--enable-preview")
}

// BootRun 은 JavaExec 의 하위 타입이라 bootRun 도 함께 걸린다.
tasks.withType<JavaExec> {
    jvmArgs("--enable-preview")
}

tasks.withType<Test> {
    useJUnitPlatform()
    jvmArgs("--enable-preview")
}
