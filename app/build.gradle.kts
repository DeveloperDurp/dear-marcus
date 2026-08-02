import org.gradle.api.publish.maven.MavenPublication

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    `maven-publish`
}

android {
    namespace = "com.dearmarcus"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.dearmarcus"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
        jvmToolchain(17)
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.mlkit.genai.prompt)
    ksp(libs.androidx.room.compiler)

    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.json)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
}

afterEvaluate {
    publishing {
        publications {
            register<MavenPublication>("releaseApk") {
                groupId = "com.dearmarcus"
                artifactId = "dear-marcus"
                version = providers.gradleProperty("packageVersion").getOrElse("0.0.0-dev")

                artifact(layout.buildDirectory.file("outputs/apk/debug/app-debug.apk")) {
                    extension = "apk"
                    builtBy(tasks.named("assembleDebug"))
                }
            }
        }
        repositories {
            maven {
                name = "GitHubPackages"
                url = uri(
                    "https://maven.pkg.github.com/${providers.environmentVariable("GITHUB_REPOSITORY").getOrElse("OWNER/REPOSITORY")}",
                )
                credentials {
                    username = providers.environmentVariable("GITHUB_ACTOR").orNull
                    password = providers.environmentVariable("GITHUB_TOKEN").orNull
                }
            }
        }
    }
}
