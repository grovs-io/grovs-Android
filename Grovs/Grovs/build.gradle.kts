import com.vanniktech.maven.publish.SonatypeHost
import java.util.Properties

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.jetbrains.kotlin.android)
    id("kotlin-parcelize")
    id("maven-publish")
    id("signing")
    id("com.vanniktech.maven.publish") version "0.31.0"
    id("jacoco")
}

val BOOLEAN = "boolean"
val STRING = "String"
val TRUE = "true"
val FALSE = "false"
val SERVER_URL = "SERVER_URL"
val SDK_VERSION = "SDK_VERSION"
val NETWORK_LOGGING = "NETWORK_LOGGING"

private val libraryGroupId = "io.grovs"
// MAVEN CENTRAL
private val libraryArtifactId = "Grovs"
private val libraryVersion = "1.1.0"
val NETWORK_LOGGING_VALUE = FALSE
// GITHUB
//private val libraryArtifactId = "grovs"
//private val libraryVersion = "1.0.17"
//val NETWORK_LOGGING_VALUE = TRUE

android {
    namespace = "io.grovs"
    compileSdk = 35

    defaultConfig {
        minSdk = 21

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
        
        // Default test server URL - will be overridden by MockWebServer in tests
        // Tests should set this via GrovsTestRule which injects the mock server URL
        buildConfigField(STRING, "TEST_SERVER_URL", "\"http://localhost:8080/\"")
    }

    buildTypes {

        val SERVER_URL_PRODUCTION = "\"https://sdk.sqd.link/api/v1/sdk/\""

        debug {
            isMinifyEnabled = false
            enableAndroidTestCoverage = true
            enableUnitTestCoverage = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )

            buildConfigField(STRING, SERVER_URL, SERVER_URL_PRODUCTION)
            buildConfigField(STRING, SDK_VERSION, "\"" + libraryVersion + "\"")
            buildConfigField(BOOLEAN, NETWORK_LOGGING, NETWORK_LOGGING_VALUE)
        }

        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )

            buildConfigField(STRING, SERVER_URL, SERVER_URL_PRODUCTION)
            buildConfigField(STRING, SDK_VERSION, "\"" + libraryVersion + "\"")
            buildConfigField(BOOLEAN, NETWORK_LOGGING, NETWORK_LOGGING_VALUE)
        }
    }

//    productFlavors {
//        val SERVER_URL_DEVELOPMENT = "\"https://sdk.sqd.link/api/v1/sdk/\""
//        val SERVER_URL_PRODUCTION = "\"https://sdk.sqd.link/api/v1/sdk/\""
//
//        create("envDevelopment") {
//            buildConfigField(STRING, SERVER_URL, SERVER_URL_DEVELOPMENT)
//            dimension = "default"
//        }
//
//        create("envProd") {
//            buildConfigField(STRING, SERVER_URL, SERVER_URL_PRODUCTION)
//            dimension = "default"
//        }
//    }
//
//    flavorDimensions.add("default")

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.useJUnitPlatform()
            it.testLogging {
                events("passed", "skipped", "failed", "standardOut", "standardError")
                showExceptions = true
                showCauses = true
                showStackTraces = true
                exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
            }
        }
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/LICENSE.md",
                "META-INF/LICENSE-notice.md",
                "META-INF/NOTICE.md"
            )
        }
    }
}

// Load local.properties for E2E test credentials
val localProps = Properties()
rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { localProps.load(it) }

tasks.withType<Test> {
    testLogging {
        events("passed", "skipped", "failed", "standardOut", "standardError")
        showExceptions = true
        showCauses = true
        showStackTraces = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStandardStreams = true
    }
    
    // Print a summary after tests complete
    afterSuite(KotlinClosure2<TestDescriptor, TestResult, Unit>({ desc, result ->
        if (desc.parent == null) {
            println("\n========================================")
            println("TEST RESULTS SUMMARY")
            println("========================================")
            println("Tests run: ${result.testCount}")
            println("Passed: ${result.successfulTestCount}")
            println("Failed: ${result.failedTestCount}")
            println("Skipped: ${result.skippedTestCount}")
            println("========================================")
            if (result.failedTestCount > 0) {
                println("❌ SOME TESTS FAILED")
            } else {
                println("✅ ALL TESTS PASSED")
            }
            println("========================================\n")
        }
    }))
}

// JaCoCo Code Coverage Configuration
jacoco {
    toolVersion = "0.8.14"
}

// Enable JaCoCo agent for unit tests with Robolectric compatibility
tasks.withType<Test> {
    extensions.configure<JacocoTaskExtension> {
        isEnabled = true
        // Required for Robolectric tests - includes classes without location info
        isIncludeNoLocationClasses = true
        // Exclude JDK internal classes (required for Java 9+)
        excludes = listOf("jdk.internal.*")
    }
}

tasks.register<JacocoReport>("jacocoTestReport") {
    dependsOn("testDebugUnitTest")
    
    reports {
        xml.required.set(true)
        html.required.set(true)
        csv.required.set(false)
        html.outputLocation.set(layout.buildDirectory.dir("reports/jacoco/html"))
        xml.outputLocation.set(layout.buildDirectory.file("reports/jacoco/jacocoTestReport.xml"))
    }
    
    val fileFilter = listOf(
        "**/R.class",
        "**/R\$*.class",
        "**/BuildConfig.*",
        "**/Manifest*.*",
        "**/*Test*.*",
        "android/**/*.*",
        "**/databinding/**",
        "**/android/databinding/*Binding.*",
        "**/BR.*",
        "**/*_MembersInjector.*",
        "**/Dagger*Component*.*",
        "**/*Module_*Factory.*"
    )
    
    val debugTree = fileTree("${buildDir}/tmp/kotlin-classes/debug") {
        exclude(fileFilter)
    }
    
    val mainSrc = "${project.projectDir}/src/main/java"
    
    sourceDirectories.setFrom(files(mainSrc))
    classDirectories.setFrom(files(debugTree))
    executionData.setFrom(fileTree(buildDir) {
        include("jacoco/testDebugUnitTest.exec")
        include("outputs/unit_test_code_coverage/debugUnitTest/testDebugUnitTest.exec")
        include("outputs/code_coverage/debugAndroidTest/connected/**/*.ec")
    })
}

tasks.register("printCoverageReport") {
    dependsOn("jacocoTestReport")
    doLast {
        val reportFile = file("${buildDir}/reports/jacoco/jacocoTestReport.xml")
        if (reportFile.exists()) {
            val xmlContent = reportFile.readText()

            fun calcPct(missed: Int, covered: Int): Double {
                val total = missed + covered
                return if (total > 0) (covered.toDouble() / total * 100) else 0.0
            }

            // Overall counters (last set of counters in the XML, direct children of <report>)
            val counterPattern = """<counter type="(\w+)" missed="(\d+)" covered="(\d+)"/>""".toRegex()
            val allCounters = counterPattern.findAll(xmlContent).toList()

            // The last 5 counters belong to the <report> element (overall)
            val overallCounters = allCounters.takeLast(5).associate { match ->
                match.groupValues[1] to Pair(match.groupValues[2].toInt(), match.groupValues[3].toInt())
            }

            println("\n========================================")
            println("OVERALL CODE COVERAGE")
            println("========================================")
            for (type in listOf("INSTRUCTION", "BRANCH", "LINE", "METHOD", "CLASS")) {
                val (missed, covered) = overallCounters[type] ?: Pair(0, 0)
                println(String.format("%-14s %6.2f%%  (%d/%d)", "$type:", calcPct(missed, covered), covered, missed + covered))
            }
            println("========================================\n")

            // Per-class coverage from <class> elements
            val classBlockPattern = """<class name="([^"]+)"[^>]*>[\s\S]*?</class>""".toRegex()
            data class ClassCoverage(
                val name: String,
                val lineMissed: Int,
                val lineCovered: Int,
                val branchMissed: Int,
                val branchCovered: Int,
                val methodMissed: Int,
                val methodCovered: Int
            ) {
                val lineTotal get() = lineMissed + lineCovered
                val linePct get() = calcPct(lineMissed, lineCovered)
                val branchPct get() = calcPct(branchMissed, branchCovered)
                val methodPct get() = calcPct(methodMissed, methodCovered)
            }

            val classes = mutableListOf<ClassCoverage>()
            classBlockPattern.findAll(xmlContent).forEach { classMatch ->
                val fullName = classMatch.groupValues[1].replace("/", ".")
                val block = classMatch.value
                val counters = counterPattern.findAll(block).associate { m ->
                    m.groupValues[1] to Pair(m.groupValues[2].toInt(), m.groupValues[3].toInt())
                }
                val (lm, lc) = counters["LINE"] ?: Pair(0, 0)
                val (bm, bc) = counters["BRANCH"] ?: Pair(0, 0)
                val (mm, mc) = counters["METHOD"] ?: Pair(0, 0)
                if (lm + lc > 0) {
                    classes.add(ClassCoverage(fullName, lm, lc, bm, bc, mm, mc))
                }
            }

            // Group by package
            val byPackage = classes.groupBy { it.name.substringBeforeLast(".") }
                .toSortedMap()

            println("========================================")
            println("PER-CLASS CODE COVERAGE")
            println("========================================")
            println(String.format("%-60s %8s %8s %8s", "Class", "Lines", "Branch", "Methods"))
            println("-".repeat(88))

            for ((pkg, pkgClasses) in byPackage) {
                println("\n  $pkg")
                for (cls in pkgClasses.sortedBy { it.name }) {
                    val shortName = cls.name.substringAfterLast(".")
                    val branchStr = if (cls.branchMissed + cls.branchCovered > 0)
                        String.format("%5.1f%%", cls.branchPct) else "  n/a"
                    println(String.format("    %-56s %5.1f%% %8s %5.1f%%",
                        shortName, cls.linePct, branchStr, cls.methodPct))
                }
            }

            println("\n========================================")
            println("HTML Report: ${buildDir}/reports/jacoco/html/index.html")
            println("========================================\n")
        } else {
            println("Coverage report not found. Run tests first.")
        }
    }
}

dependencies {
    compileOnly(libs.firebase.messaging.ktx)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)

    implementation(libs.retrofit)
    implementation(libs.gson)
    implementation(libs.okhttp)
    implementation(libs.converter.gson)
    implementation(libs.logging.interceptor)

    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.livedata.core.ktx)

    implementation(libs.androidx.constraintlayout)

    //noinspection GradleDynamicVersion
    implementation(libs.installreferrer)
    implementation(libs.androidx.navigation.fragment.ktx)
    implementation(libs.androidx.navigation.ui.ktx)

    testImplementation(libs.junit)
    testImplementation(libs.junit.jupiter.api)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testImplementation(libs.junit.jupiter.params)
    testRuntimeOnly(libs.junit.vintage.engine)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation("io.mockk:mockk:1.13.13")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    // AndroidX Test for better Android component testing
    testImplementation("androidx.test:core-ktx:1.5.0")
    testImplementation("androidx.test.ext:junit-ktx:1.1.5")
    testImplementation("androidx.arch.core:core-testing:2.2.0")
    // Robolectric for Android runtime simulation - requires 4.14+ for Java 24 support
    testImplementation("org.robolectric:robolectric:4.16.1")
    
    // Instrumented test dependencies
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.mockwebserver)
    testImplementation(libs.mockwebserver)
    androidTestImplementation(libs.mockk.android)
    androidTestImplementation(libs.coroutines.test)
    androidTestImplementation(libs.androidx.test.core.ktx)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.fragment.testing)

}

project.afterEvaluate {
    publishing {
        repositories {
            maven {
                name = "GithubPackagesPrivate"
                url = uri("https://maven.pkg.github.com/grovs-io/grovs-android-automation-app")
                credentials(PasswordCredentials::class)
            }
        }

        publications {
            create<MavenPublication>("release") {
                groupId = libraryGroupId
                artifactId = libraryArtifactId
                version = libraryVersion
                //artifact(tasks["bundleEnvProdReleaseAar"])
                artifact(tasks["bundleReleaseAar"])
                artifact(tasks["androidSourcesJar"])

                pom {
                    name.set("Grovs")
                    description.set("Grovs is a powerful SDK that enables deep linking and universal linking within your mobile and web applications.")
                }
            }
        }
    }
}

tasks.register("androidSourcesJar", Jar::class) {
    archiveClassifier.set("sources")

    if (project.plugins.hasPlugin("com.android.library")) {
        // For Android libraries
        from(android.sourceSets["main"].java.srcDirs)
    } else {
        // For pure Kotlin libraries
        from(sourceSets["main"].java.srcDirs)
        from(kotlin.sourceSets["main"].kotlin.srcDirs)
    }
}

mavenPublishing {
    // Define coordinates for the published artifact
    coordinates(
        groupId = libraryGroupId,
        artifactId = libraryArtifactId,
        version = libraryVersion
    )

    // Configure POM metadata for the published artifact
    pom {
        name.set("Grovs")
        description.set("Grovs is a powerful SDK that enables deep linking and universal linking within your mobile and web applications.")
        inceptionYear.set("2024")
        url.set("https://github.com/grovs-io/grovs-Android")
        licenses {
            license {
                name.set("The Apache Software License, Version 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
            }
        }
        // Specify developers information
        developers {
            developer {
                id.set("chelemen-razvan")
                name.set("Chelemen Razvan")
                email.set("razvan@appssemble.com")
            }
        }
        // Specify SCM information
        scm {
            url.set("https://github.com/grovs-io/grovs-Android")
        }

    }

    // Configure publishing to Maven Central
    publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL)
    // Enable GPG signing for all publications
    signAllPublications()
}