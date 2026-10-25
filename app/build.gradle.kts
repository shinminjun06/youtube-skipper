plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
 namespace = "com.pivotry.blockauto"
 compileSdk = 35
 defaultConfig {
  applicationId = "com.pivotry.blockauto"; minSdk = 30; targetSdk = 34
  versionCode = 26; versionName = "1.3.0"
  testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
 }
 buildFeatures { buildConfig = true }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
 kotlinOptions { jvmTarget = "17" }
}
dependencies {
 implementation("com.google.mlkit:text-recognition:16.0.1")
 androidTestImplementation("androidx.test:runner:1.6.2")
 androidTestImplementation("androidx.test.ext:junit:1.2.1")
 androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
}
