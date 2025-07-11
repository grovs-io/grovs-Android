import com.vanniktech.maven.publish.SonatypeHost

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.jetbrains.kotlin.android)
    id("kotlin-parcelize")
    id("maven-publish")
    id("signing")
    id("com.vanniktech.maven.publish") version "0.31.0"
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
private val libraryVersion = "1.0.13"
val NETWORK_LOGGING_VALUE = FALSE
// GITHUB
//private val libraryArtifactId = "grovs"
//private val libraryVersion = "1.0.7-test-debug2"
//val NETWORK_LOGGING_VALUE = TRUE

android {
    namespace = "io.grovs"
    compileSdk = 34

    defaultConfig {
        minSdk = 21

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {

        val SERVER_URL_PRODUCTION = "\"https://sdk.sqd.link/api/v1/sdk/\""

        debug {
            isMinifyEnabled = false
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
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)

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