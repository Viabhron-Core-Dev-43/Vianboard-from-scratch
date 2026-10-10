plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.secrets)
}

android {
  namespace = "com.example"
  compileSdk { version = release(36) { minorApiLevel = 1 } }

  defaultConfig {
    applicationId = "shura.vianboard.yljgep"
    minSdk = 24
    targetSdk = 36
    versionCode = 4100
    versionName = "4.1-beta1"

    resourceConfigurations += listOf("en", "fr")
    ndk {
      abiFilters.addAll(listOf("arm64-v8a", "armeabi-v7a"))
    }

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }



  val keystorePath = System.getenv("DEBUG_KEYSTORE_PATH")
  if (!keystorePath.isNullOrEmpty() && file(keystorePath).exists()) {
    signingConfigs {
      create("customDebug") {
        storeFile = file(keystorePath)
        storePassword = System.getenv("DEBUG_STORE_PASSWORD") ?: "android"
        keyAlias = System.getenv("DEBUG_KEY_ALIAS") ?: "androiddebugkey"
        keyPassword = System.getenv("DEBUG_KEY_PASSWORD") ?: "android"
      }
    }
  }

  buildTypes {
    release {
      isCrunchPngs = false
      isMinifyEnabled = true
      isShrinkResources = true
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
    }
    debug {
      val customDebug = signingConfigs.findByName("customDebug")
      if (customDebug != null) {
        signingConfig = customDebug
      }
    }
  }
  compileOptions {
    isCoreLibraryDesugaringEnabled = true
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  buildFeatures {
    viewBinding = false
    compose = false
    buildConfig = true
  }

  testOptions { unitTests { isIncludeAndroidResources = true } }
  dependenciesInfo {
    includeInApk = false
    includeInBundle = false
  }
}

secrets {
  propertiesFileName = ".env"
  defaultPropertiesFileName = ".env.example"
}

dependencies {
  coreLibraryDesugaring(libs.desugar.jdk.libs)
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.activity)
  implementation(libs.androidx.recyclerview)
  implementation(libs.androidx.autofill)
  implementation(libs.androidx.viewpager2)
  implementation(libs.kotlinx.serialization.json)
  testImplementation(libs.junit)
  testImplementation(libs.robolectric)
}
