import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

/**
 * 客户端 GUID 属于设备专属凭据：相机只接受白名单内的那一组，
 * 因此不进版本库，构建时从 local.properties（或环境变量）读入。
 * 没配置时留空，App 会在连接时给出明确提示，而不是静默用一个假值。
 */
val ptpClientGuid: String = run {
    val props = Properties()
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { props.load(it) }
    (props.getProperty("ptp.client.guid") ?: System.getenv("PTP_CLIENT_GUID") ?: "")
        .trim()
        .removePrefix("\"").removeSuffix("\"")
}

android {
    namespace = "com.omphone.capture"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.omphone.capture"
        minSdk = 26
        targetSdk = 34
        versionCode = 9
        versionName = "1.8"

        buildConfigField("String", "PTP_CLIENT_GUID", "\"$ptpClientGuid\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            applicationIdSuffix = ""
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.11"
    }
    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
    }

    // android.util.Log 在 JVM 单测里是 stub，会抛 "not mocked"；返回默认值即可
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-service:2.7.0")
    implementation("androidx.activity:activity-compose:1.9.0")

    implementation(platform("androidx.compose:compose-bom:2024.05.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    testImplementation("junit:junit:4.13.2")
}
