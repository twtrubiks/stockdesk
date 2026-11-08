import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// 純 Kotlin，沒有 Android：資料來源（證交所、櫃買、集保、期交所）、解析、節流、快取規則、自選股。
// 這裡的程式在 JVM 單元測試裡幾秒就能跑完（MockWebServer ＋ 錄好的真實回應）
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    // src/testFixtures：照官方格式造的假回應（Samples），core 的 JVM 測試與 app 模擬器上的 FakeSources 共用
    `java-test-fixtures`
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

tasks.withType<Test>().configureEach {
    // CI 只留得下 gradle-build.txt，測試失敗要直接印出期望值與實際值
    testLogging { exceptionFormat = TestExceptionFormat.FULL }
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.jsoup)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockwebserver3)
    testImplementation(libs.sqlite.jdbc)
}
